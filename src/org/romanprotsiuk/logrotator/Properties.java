package org.romanprotsiuk.logrotator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

public class Properties extends java.util.Properties {

	private static final long serialVersionUID = 1L;

	public static final String NL = System.getProperty("line.separator");

	public static final String[] LOG_ENTRY_COMPOSITION;
	public static final Map<String, String> LOG_PATTERN_PARTS;

	public static final Map<String, String> PATTERNS;

	// Patterns
	public static final List<Pattern> METHOD_INFO_IN_STACK_LIST;

	public static final List<Pattern> DUPLICATORS_LIST;

	public static final List<Pattern> MESSAGE_ON_SECOND_LINE_LIST;

	public static final Pattern LOG;

	public static final Pattern SIGNIFICANT_STACK_INFO;

	public static final Pattern CAUSED_BY;

	public static final List<Pattern> IGNORE_LIST;

	private static Properties instance;
	static {
		instance = new Properties();

		Set<String> names = instance.stringPropertyNames();

		LOG_PATTERN_PARTS = new HashMap<String, String>();
		PATTERNS = new HashMap<String, String>();

		for (String name : names)
			if (name.endsWith("_PART"))
				LOG_PATTERN_PARTS.put(name, instance.getProperty(name));
			else if (name.endsWith("_PATTERN")) PATTERNS.put(name, instance.getProperty(name));

		LOG_ENTRY_COMPOSITION = instance.getProperty("LOG_ENTRY_COMPOSITION",
				"LOG_DATE_PART,SEVERITY_PART,THREAD_NAME_PART,METHOD_PART,MESSAGE_PART").split(",");

		// Patterns compilation
		LOG = logPattern(new HashMap<String, String>());

		SIGNIFICANT_STACK_INFO = Pattern.compile(instance.getProperty("SIGNIFICANT_STACK_INFO"),
				Pattern.MULTILINE);
		CAUSED_BY = Pattern.compile(instance.getProperty("CAUSED_BY"), Pattern.MULTILINE);

		// for (String name : names)
		// if (name.endsWith("_LIST"))
		// LISTS.put(name, buildList(instance.getProperty(name, "")));

		METHOD_INFO_IN_STACK_LIST = buildList(instance.getProperty("METHOD_INFO_IN_STACK_LIST", ""));
		DUPLICATORS_LIST = buildList(instance.getProperty("DUPLICATORS_LIST", ""));
		MESSAGE_ON_SECOND_LINE_LIST = buildList(instance.getProperty("MESSAGE_ON_SECOND_LINE_LIST", ""));
		IGNORE_LIST = buildList(instance.getProperty("IGNORE_LIST", ""));
	}

	private static List<Pattern> buildList(String list) {
		if (list == null || list.length() == 0) return Collections.emptyList();
		List<Pattern> result = new ArrayList<Pattern>();
		for (String s : list.split(","))
			result.add(getPattern(s));
		return result;
	}

	public static Pattern getPattern(String name) {
		if (!PATTERNS.containsKey(name)) throw new RuntimeException("Pattern not found: " + name);
		String p = PATTERNS.get(name);
		if (name.endsWith("_ERROR_PATTERN")) return errorPattern(p);
		return anyPattern(p);
	}

	public static Pattern logPattern(Map<String, String> parts) {
		String pattern = "";
		for (String p : LOG_ENTRY_COMPOSITION)
			pattern += parts.containsKey(p) ? parts.get(p) : LOG_PATTERN_PARTS.get(p);
		return Pattern.compile(pattern, Pattern.MULTILINE);
	}

	public static Pattern anyPattern(String methodAndMessage) {
		Map<String, String> parts = new HashMap<String, String>();
		parts.put("METHOD_PART", methodAndMessage);
		parts.put("MESSAGE_PART", "");
		return logPattern(parts);
	}

	public static Pattern errorPattern() {
		Map<String, String> parts = new HashMap<String, String>();
		parts.put("SEVERITY_PART", "(ERROR|FATAL)");
		return logPattern(parts);
	}

	public static Pattern errorPattern(String methodAndMessage) {
		Map<String, String> parts = new HashMap<String, String>();
		parts.put("SEVERITY_PART", "(ERROR|FATAL)");
		parts.put("METHOD_PART", methodAndMessage);
		parts.put("MESSAGE_PART", "");
		return logPattern(parts);
	}

	private Properties() {
		String baseFileName = "logrotator";
		String propertiesFileName = baseFileName + ".properties";
		InputStream is = null;
		try {
			if (Thread.currentThread().getContextClassLoader() != null)
				is = Thread.currentThread().getContextClassLoader().getResourceAsStream(propertiesFileName);
			else
				is = (Properties.class).getResourceAsStream(propertiesFileName);
			if (is != null)
				load(is);
			else
				logger.warn("File not found for Properties(\"" + baseFileName + "\")");
		} catch (IOException e) {
			logger.error("Failed initializing Properties(\"" + baseFileName + "\")", e);
		} finally {
			if (is != null) try {
				is.close();
			} catch (Exception e) {
			}
		}
	}

	static Logger logger = Logger.getLogger(Properties.class);

}
