package org.romanprotsiuk.logrotator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

public class LogRotator {

	static Logger logger = Logger.getLogger(LogRotator.class);

	public static void main(String[] args) throws Exception {
		String logsFolder = "/Users/romanprotsiuk/logs/webapp_latest";
		splitByDate(logsFolder + "/webapp.log", logsFolder + "/filtered");
		extractErrors(logsFolder + "/webapp.log", logsFolder + "/errors");
	}

	public static void splitByDate(String inFolder, String outFolder) throws Exception {
		LogRotator rotator = new LogRotator(null, Properties.IGNORE_LIST);
		rotator.splitByError = false;
		rotator.ignoreDuplicates = false;
		ExecutorService executor = rotator.rotate(inFolder, outFolder);
		executor.shutdown();
		rotator.startProgressTask();
	}

	public static void extractErrors(String inFolder, String outFolder) throws Exception {
		LogRotator rotator = new LogRotator(Arrays.asList(Properties.errorPattern()));
		rotator.splitByError = true;
		rotator.ignoreDuplicates = false;
		ExecutorService executor = rotator.rotate(inFolder, outFolder);
		executor.shutdown();
		rotator.startProgressTask();
	}

	public static void extractLogEntries(String inFolder, String outFolder) throws Exception {
		LogRotator rotator = new LogRotator(Arrays.asList(Properties.LOG), null, null, true);
		ExecutorService executor = rotator.rotate(inFolder, outFolder);
		executor.shutdown();
		rotator.startProgressTask();
	}

	public static void filterStackTraces(String inFolder, String outFolder) throws Exception {
		LogRotator rotator = new LogRotator(null, Arrays.asList(Pattern.compile("^\\s+at .+\\)",
				Pattern.MULTILINE)), null, true);
		ExecutorService executor = rotator.rotate(inFolder, outFolder);
		executor.shutdown();
		rotator.startProgressTask();
	}

	public LogRotator() {
		this(null, null);
	}

	public LogRotator(Collection<Pattern> includePatterns) {
		this(includePatterns, null);
	}

	public LogRotator(Collection<Pattern> includePatterns, Collection<Pattern> skipPatterns) {
		this(includePatterns, skipPatterns, null);
	}

	public LogRotator(Collection<Pattern> includePatterns, Collection<Pattern> skipPatterns, Collection<Pattern> dupPatterns) {
		this(includePatterns, skipPatterns, dupPatterns, false);
	}

	public LogRotator(Collection<Pattern> includePatterns, Collection<Pattern> skipPatterns, Collection<Pattern> dupPatterns, boolean checkTailLines) {
		this.conditions = new LogConditions(checkTailLines, includePatterns, skipPatterns, dupPatterns);
	}

	public LogRotator(LogConditions conditions) {
		this.conditions = new LogConditions(conditions);
	}
	
	public LogRotator(LogRotator rotator) {
		this(rotator.conditions);
		this.splitByError = rotator.splitByError;
		this.ignoreDuplicates = rotator.ignoreDuplicates;
		this.percLimit = rotator.percLimit;
	}
	
	private boolean ignoreDuplicates = false;
	
	private boolean splitByError = true;
	
	private int percLimit = 0;
	
	public static ExecutorService getExecutor() {
		final Runtime runtime = Runtime.getRuntime();
        final int processorsCount = runtime.availableProcessors();
        final int threadsCount = Math.max(2, processorsCount) * 2;
        
        return Executors.newFixedThreadPool(threadsCount);
	}
	
	public void merge(String inputPath, String output) throws Exception {
		File in = new File(inputPath);
		if (!in.exists()) {
			throw new RuntimeException("Input doesn't exist");
		}
		Writer w = RotateWriter.createWriter(output, false);
		File[] files = in.isDirectory() ? in.listFiles(RotateWriter.regularFiles) : new File[] {in};
		try {
			for (final File file : files) {
				logger.debug("Merging: " + file.getName());
				BufferedReader r = new BufferedReader(new FileReader(file));
				int i = 0;
				int size = 0;
				int bufferSize = 4096;
				char[] buffer = new char[bufferSize];
				try {
					while ((i = r.read(buffer, 0, bufferSize)) >= 0) {
						w.write(buffer, 0, i);
						size += i;
					}
				} finally {
					r.close();
				}
			}
		} finally {
			w.close();
		}
	}
	
	public ExecutorService rotate(String inputPath, String outputFolder) throws Exception {
		return rotate(inputPath, outputFolder, true);
	}
	
	public ExecutorService rotate(String inputPath, String outputFolder, boolean multithread) throws Exception {
		File in = new File(inputPath);
		if (!in.exists()) {
			throw new RuntimeException("Input doesn't exist: " + inputPath);
		}
		
		RotateWriter.checkOutput(outputFolder, false);
		
		File[] files = in.isDirectory() ? in.listFiles(RotateWriter.regularFiles) : new File[] {in};
		rotators = new LinkedList<Rotator>();
		ExecutorService executor = multithread ? getExecutor() : null;
		for (File file : files) {
			if (multithread) {
				Rotator r = new Rotator(this, file, outputFolder);
				rotators.add(r);
				executor.execute(r);
			} else
				rotate(file, outputFolder);
		}
		return executor;
	}
	List<Rotator> rotators;
	
	private class Rotator implements Runnable {
		private LogRotator parent;
		private File file;
		private String outputFolderPath;
		public LogRotator worker;
		public Rotator(LogRotator parent, File file, String outputFolderPath) {
			this.parent = parent;
			this.file = file;
			this.outputFolderPath = outputFolderPath;
		}
		
		public boolean running() {
			return worker != null;
		}
		
		public boolean done() {
			return running() && worker.done;
		}
		
		public String getFileName() {
			return file.getName();
		}
		
		public double getProgress() {
			return running() ? worker.progress : 0.0;
		}
		
		@Override
		public void run() {
			worker = new LogRotator(parent);
			try {
				worker.rotate(file, outputFolderPath);
			} catch (Exception e) {
				throw new RuntimeException(e);
			} finally {
				parent.rotators.remove(this);
			}
		}
	}
	
	private LogConditions conditions = null;
	private double progress = 0.0;
	private boolean done = true;
	
	public void rotate(File file, String outputFolder) throws FileNotFoundException, IOException {
		rotate(createReader(file), outputFolder);
	}

	public void rotate(BufferedReader r, String outputFolder) throws FileNotFoundException, IOException {
		RotateWriter.checkOutput(outputFolder, false);
		
		progress = 0.0;
		done = false;
		
		String line = null;
		RotateWriter w = new RotateWriter(conditions, splitByError ? null : fileName);
		try {
			while (!done) {
				line = r.readLine();
				done = line == null;
				if (done || w.isLogStart(line)) {
					if (w.notEmpty()) {
						if (!w.isDuplicate()) {
							if (w.filterLines())
								w.switchFile(outputFolder);
						} else if (ignoreDuplicates)
							w.clearLines();
						
						w.flushLines();
						w.nextStep();
					}
				}
				
				if (!done) {
					w.addLine(line);

					progress += line.getBytes().length * fileSizePerc;

					if (percLimit > 0 && progress > percLimit) {
						logger.warn("Limit reached. There's still log to rotate.");
						break;
					}
				}
			}
			logger.debug("Completed processing " + fileName);
		} finally {
			r.close();
			try {
				w.flushLines();
			} finally {
				w.closeWriter();
			}
		}
	}
	
	private String fileName = "";
	private double fileSizePerc = 0.0;
	
	private BufferedReader createReader(File file) throws FileNotFoundException {
		if (!file.exists() || !file.isFile()) {
			throw new RuntimeException("Input doesn't exist");
		}
		
		fileName = file.getName();
		fileSizePerc = 100.0/file.length();
		
		BufferedReader r = new BufferedReader(new FileReader(file));
		return r;
	}
	
	private void startProgressTask() {
		new ProgressTask(15000).start();
	}
	
	private class ProgressTask extends TimerTask {
		
		public ProgressTask(long delay) {
			this.delay = delay;
		}
		
		private Timer t;
		private long delay;
		private Date started = new Date();
		
		public void start() {
			t = new Timer();
			Calendar c = new GregorianCalendar();
			c.add(Calendar.SECOND, 4);
			t.schedule(this, delay, delay);
		}
		
		@Override
		public void run() {
			boolean running = false;
			int queued = 0;
			for (Rotator r : rotators)
				if (!r.done()) {
					if (r.running()) {
						double progress = r.getProgress();
						long timePassed = new Date().getTime() - started.getTime();
						long eta = Math.round(timePassed * (100.0 / progress - 1.0)) / 1000;
						logger.debug(String.format("%s is processed %.2f%%, ETA %d:%02d", r.getFileName(), progress, eta / 60, eta % 60));
					} else {
						queued++;
					}
					running = true;
				}
			if (queued > 0)
				logger.debug(queued + " files in queue");
			if (!running) {
				t.cancel();
				rotators = new LinkedList<Rotator>();
			}
		}
	}
}
