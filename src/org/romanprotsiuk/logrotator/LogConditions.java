package org.romanprotsiuk.logrotator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class LogConditions {
	
	public LogConditions() {
		this(false);
	}
	
	public LogConditions(boolean checkTailLines) {
		this(checkTailLines, null);
	}
	
	public LogConditions(boolean checkTailLines, Collection<Pattern> includePatterns) {
		this(checkTailLines, includePatterns, null);
	}

	public LogConditions(boolean checkTailLines, Collection<Pattern> includePatterns, Collection<Pattern> skipPatterns) {
		this(checkTailLines, includePatterns, skipPatterns, Properties.DUPLICATORS_LIST);
	}
	
	public LogConditions(boolean checkTailLines, Collection<Pattern> includePatterns, Collection<Pattern> skipPatterns,
			Collection<Pattern> dupPatterns) {
		this.checkTailLines = checkTailLines;
		this.includePatterns = includePatterns;
		this.skipPatterns = skipPatterns;
		this.dupPatterns = dupPatterns;
	}

	public LogConditions(LogConditions conditions) {
		this.checkTailLines = conditions.checkTailLines;
		this.includePatterns = conditions.includePatterns;
		this.skipPatterns = conditions.skipPatterns;
		this.dupPatterns = conditions.dupPatterns;
	}

	private boolean checkTailLines;
	
	private Collection<Pattern> includePatterns;
	
	private Collection<Pattern> skipPatterns;
	
	private Collection<Pattern> dupPatterns;
	
	public static String firstLine(String lines) {
		return getLine(lines, 0);
	}
	
	public static String getLine(String lines, int idx) {
		int nlIdx = -1;
		int prevNlIdx;
		do {
			prevNlIdx = nlIdx + 1;
			nlIdx = lines.indexOf(Properties.NL, prevNlIdx);
			idx--;
		} while (idx >= 0 && nlIdx > -1);
		return nlIdx != -1 ? lines.substring(prevNlIdx, nlIdx) : lines.substring(prevNlIdx);
	}
	
	public String filter(String lines) {
		if (lines == null) return lines;
		if (checkTailLines) {
			StringBuilder result = new StringBuilder();
			for (String line : lines.split(Properties.NL))
				if (include(line) && !skip(line))
					result.append(line);
			return result.length() != 0 ? result.toString() : null;
		} else {
			String line = firstLine(lines);
			return include(line) && !skip(line) ? lines : null;
		}
	}

	public boolean include(String line) {
		return includePatterns == null || checkConditions(line, includePatterns);
	}

	public boolean skip(String line) {
		return skipPatterns != null && checkConditions(line, skipPatterns);
	}

	public static boolean checkConditions(String line, Collection<Pattern> conditions) {
		if (line == null) return false;
		for (Pattern p : conditions)
			if (p.matcher(line).find())
				return true;
		return false;
	}
	
	public boolean isDuplicate(String lines, String previousLines) {
		if (dupPatterns == null || lines == null || previousLines == null) return false;
		String firstLine = firstLine(lines);
		if (!checkConditions(firstLine, dupPatterns)) return false;
		
		List<String> tokens = new ArrayList<String>();
		
		if (firstLine != null) {
			String following = getLine(lines, 1);
			int i = following.indexOf(": ");
			tokens.add(i != -1 ? following.substring(i + 2) : following);
		}
		
		Matcher m = Properties.CAUSED_BY.matcher(lines);
		while (m.find())
			tokens.add(m.group(1));
		
		for (String t : tokens)
			if (previousLines.contains(t))
				return true;
		
		return false;
	}
}