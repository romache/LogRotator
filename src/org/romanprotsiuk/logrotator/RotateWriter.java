package org.romanprotsiuk.logrotator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


class RotateWriter {
	
	private static final String LOG_NAME_PATTERN = "%s.%s.log";
	private static final int FILE_NAME_MAX_LENGTH = 64;

	public RotateWriter(LogConditions conditions) {
		this(conditions, null);
	}

	public RotateWriter(LogConditions conditions, String fileNameBase) {
		this.conditions = conditions;
		this.fileNameBase = fileNameBase;
	}
	
	public String date = null;
	public String previousLines = null;		
	public Writer w = null;
	public StringBuilder b = new StringBuilder();
	public StringBuilder initialLog = null;
	
	private String fileNameBase = null;
	private LogConditions conditions;
	
	private String lines = null;
	private String filteredLines = null;
	private boolean filtered = false;
	private int bufferLength = 0;
	
	private boolean bufferNotEmpty() {
		return b != null && b.length() != 0;
	}
	
	public String getLines() {
		if (bufferNotEmpty()) {
			if (b.length() != bufferLength) {
				lines = b.toString();
				if (filtered)
					filterLines();
				bufferLength = b.length();
			}
		} else if (bufferLength != 0) {
			lines = null;
			if (filtered)
				filteredLines = null;
			bufferLength = 0;
		}
		return filtered ? filteredLines : lines;
	}
	
	public void nextStep() {
		clearFilter();
		previousLines = getLines();
		b = new StringBuilder();
		bufferLength = 0;
		clearLines();
	}
	
	public void clearLines() {
		lines = null;
		clearFilter();
	}
	
	public void clearFilter() {
		filteredLines = null;
		filtered = false;
	}
	
	public void clearPreviousLines() {
		previousLines = null;
	}

	public boolean isDuplicate() {
		return conditions.isDuplicate(getLines(), previousLines);
	}
	
	public boolean filterLines() {
		filteredLines = conditions.filter(lines);
		filtered = true;
		return notEmpty();
	}
	
	public boolean notEmpty() {
		return getLines() != null;
	}
	
	public void closeWriter() {
		if (w == null) return;
		try {
			w.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public void setWriter(Writer writer) {
		closeWriter();
		w = writer;
	}
	
	public void flushLines() {
		flushLines(true);
	}
	
	public void flushLines(boolean flushInitialLog) {
		if (notEmpty() && w != null) {
			try {
				if (flushInitialLog && initialLog != null) {
					w.write(initialLog.toString());
					initialLog = null;
				}
				w.write(getLines());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	public RotateWriter addLine(String line) {
		return addLine(line, true);
	}
	
	public RotateWriter addLine(String line, boolean addNL) {
		b.append(line);
		if (addNL)
			b.append(Properties.NL);
		return this;
	}
	
	public void switchFile(String outputFolder) throws IOException {
		if (fileNameBase == null) {
			StackMethodId mid = extractMethodId(getLines());
			if (mid.method != null && mid.message != null) {
				String dirName = outputFolder + "/" + mid.className.replaceAll("\\\\|/|:|<|>", "_");
				RotateWriter.checkOutput(dirName, false);
				dirName += "/" + mid.method.replaceAll("\\\\|/|:|<|>", "_");
				RotateWriter.checkOutput(dirName, false);
				
				String name = refineMessage(mid.message).replaceAll("\\\\|/|:|<|>", "_");
				if (name.length() > FILE_NAME_MAX_LENGTH)
					name = name.substring(0, FILE_NAME_MAX_LENGTH);
				if (mid.lineNo != null)
					name = mid.lineNo + "_" + name;
				
				setWriter(createWriter(dirName + "/" + name + ".log", true));
			} else {
				LogRotator.logger.warn("Cannot create error log file name for:\n" + getLines());
				initialLog = b;
			}
		} else {
			Matcher m = Properties.LOG.matcher(LogConditions.firstLine(getLines()));
			if (!m.find())
				throw new RuntimeException("Log entry not found");
			String dateMarker = m.group(1);
			if (dateMarker == null && date != null || !dateMarker.equals(date)) {
				date = dateMarker;
				if (date != null) {
					setWriter(createNewLog(outputFolder, fileNameBase, date));
				} else {
					LogRotator.logger.warn("Cannot create error log file name from null-date for:\n" + getLines());
					initialLog = b;
				}
			}
		}
	}
	
	private String refineMessage(String msg) {
		if (msg.contains("Wrong subscription result"))
			return "Wrong subscription result";
		if (msg.contains("Duplicate entry"))
			return "Duplicate entry";
		return msg;
	}

	private StackMethodId extractMethodId(String lines) {
		String firstLine = LogConditions.firstLine(lines);
		boolean found = false;
		boolean msgOnNextLine = false;
		Matcher m = null;
		String method = null;
		String className = null;
		String lineNo = null;
		String msg = null;
		for (Pattern p : Properties.METHOD_INFO_IN_STACK_LIST) {
			m = p.matcher(firstLine);
			if (m.find()) {
				found = true;
				msgOnNextLine = Properties.MESSAGE_ON_SECOND_LINE_LIST.contains(p);
				break;
			}
		}
		if (found) {
			msg = msgOnNextLine ? LogConditions.getLine(lines, 1) : m.group(2);
			if (msg.contains("Duplicate entry "))
				msg = "Duplicate entry";

			m = Properties.SIGNIFICANT_STACK_INFO.matcher(lines);
			while (m.find()) {
				className = m.group(1);
				method = m.group(2);
				lineNo = m.group(4);
			}
			if (method != null)
				return new StackMethodId(className, method, (lineNo != null ? lineNo : ""), msg);
		}
		
		m = Properties.LOG.matcher(firstLine);
		if (m.find()) {
			method = m.group(2);
			className = m.group(3);
			lineNo = m.group(4);
			msg = m.group(5);
		}
		return new StackMethodId(className, method, (lineNo != null ? lineNo : ""), msg);
	}
	
	class StackMethodId {
		public StackMethodId(String className, String method, String lineNo, String message) {
			this.method = method;
			this.className = className;
			this.lineNo = lineNo;
			this.message = message;
		}
		public String method;
		public String className;
		public String lineNo;
		public String message;
	}

	public static Writer createNewLog(String outputFolder, String inputName, String date) throws IOException {
		String logName = inputName;
		if (!inputName.contains(date)) {
			String name = inputName.substring(0, inputName.lastIndexOf("."));
			logName = String.format(RotateWriter.LOG_NAME_PATTERN, name, date);
		}
		return createWriter(outputFolder + "/" + logName, true);
	}
	
	public static Writer createWriter(String fileName, boolean append) throws IOException {
		File file = new File(fileName);
		if (!file.exists())
			LogRotator.logger.debug("Creating new file: " + file.getName());
		else if (!append)
			throw new RuntimeException("Output file already exists: " + fileName);
		return new BufferedWriter(new FileWriter(file, append));
	}

	public boolean isLogStart(String line) {
		return Properties.LOG.matcher(line).find();
	}

	public static FileFilter regularFiles = new FileFilter() {
		@Override
		public boolean accept(File file) {
			return file.isFile();
		}
	};

	public static File checkOutput(String outFolder, boolean clean) {
		File out = new File(outFolder);
		if (out.exists() && out.isDirectory()) {
			if (clean)
				for (File f : out.listFiles(RotateWriter.regularFiles))
					f.delete();
		} else if (!out.exists()) {
			out.mkdir();
		} else {
			throw new RuntimeException("Output is not a directory: " + outFolder);
		}
		return out;
	}
}