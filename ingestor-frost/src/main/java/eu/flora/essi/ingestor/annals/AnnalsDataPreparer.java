package eu.flora.essi.ingestor.annals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/**
 * Prepares raw Annals data for ingestion: copies regional CSV files, extracts ZIP
 * archives, and sorts OSSERVAZIONI CSV files into a separate {@code processed/} tree.
 */
public final class AnnalsDataPreparer {

    public static final String PROCESSED_DIR = "processed";

    private static final List<String> SORT_FIELDS = Arrays.asList(
	    "COMPARTIMENTO",
	    "ALIAS_BACINO",
	    "ALIAS_STAZIONE",
	    "GRANDEZZA",
	    "ANNO",
	    "MESE",
	    "GIORNO");

    private static final List<String> NUMERIC_SORT_FIELDS = Arrays.asList("ANNO", "MESE", "GIORNO");

    private AnnalsDataPreparer() {
    }

    public static PrepareResult prepare(Path rawRoot, boolean forceOverwrite) throws IOException {
	return prepare(rawRoot, rawRoot.resolve(PROCESSED_DIR), forceOverwrite);
    }

    public static PrepareResult prepare(Path rawRoot, Path processedRoot, boolean forceOverwrite) throws IOException {
	if (!Files.isDirectory(rawRoot)) {
	    throw new IOException("Raw data directory not found: " + rawRoot);
	}

	Files.createDirectories(processedRoot);
	System.out.println("Preparing Annals data");
	System.out.println("  Raw folder: " + rawRoot.toAbsolutePath());
	System.out.println("  Processed folder: " + processedRoot.toAbsolutePath());
	System.out.println("  Force overwrite: " + forceOverwrite);

	PrepareResult result = new PrepareResult();
	copyRegionalFiles(rawRoot, processedRoot, forceOverwrite, result);
	Set<Path> extractedOutputs = extractZipArchives(rawRoot, processedRoot, forceOverwrite, result);
	sortObservationCsvs(processedRoot, forceOverwrite, extractedOutputs, result);

	System.out.println("Preparation summary:");
	System.out.println("  Regional files copied: " + result.regionalFilesCopied);
	System.out.println("  Zip archives extracted: " + result.zipArchivesExtracted);
	System.out.println("  Observation CSV files sorted: " + result.observationCsvsSorted);

	if (!result.didWork()) {
	    System.out.println("No files were prepared (already up to date or no input files found).");
	}
	return result;
    }

    private static void copyRegionalFiles(Path rawRoot, Path processedRoot, boolean forceOverwrite, PrepareResult result)
	    throws IOException {
	Files.walkFileTree(rawRoot, new SimpleFileVisitor<Path>() {
	    @Override
	    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
		if (shouldSkipPath(rawRoot, dir)) {
		    return FileVisitResult.SKIP_SUBTREE;
		}
		return FileVisitResult.CONTINUE;
	    }

	    @Override
	    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
		if (shouldSkipPath(rawRoot, file)) {
		    return FileVisitResult.CONTINUE;
		}

		Path relative = rawRoot.relativize(file);
		if (relative.getNameCount() < 2) {
		    return FileVisitResult.CONTINUE;
		}
		if (isZip(file)) {
		    return FileVisitResult.CONTINUE;
		}

		Path target = processedRoot.resolve(relative);
		if (!forceOverwrite && Files.exists(target)
			&& Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(file)) >= 0) {
		    return FileVisitResult.CONTINUE;
		}

		Files.createDirectories(target.getParent());
		Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
		result.regionalFilesCopied++;
		System.out.println("Copied: " + relative);
		return FileVisitResult.CONTINUE;
	    }
	});
    }

    private static Set<Path> extractZipArchives(Path rawRoot, Path processedRoot, boolean forceOverwrite, PrepareResult result)
	    throws IOException {
	Set<Path> extractedOutputs = new HashSet<>();
	List<Path> zipFiles = new ArrayList<>();
	Files.walkFileTree(rawRoot, new SimpleFileVisitor<Path>() {
	    @Override
	    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
		if (shouldSkipPath(rawRoot, dir)) {
		    return FileVisitResult.SKIP_SUBTREE;
		}
		return FileVisitResult.CONTINUE;
	    }

	    @Override
	    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
		if (!shouldSkipPath(rawRoot, file) && isZip(file)) {
		    zipFiles.add(file);
		}
		return FileVisitResult.CONTINUE;
	    }
	});

	for (Path zipPath : zipFiles) {
	    Path relative = rawRoot.relativize(zipPath);
	    Path targetDir = processedRoot.resolve(relative.getParent());
	    if (extractZipArchive(zipPath, targetDir, forceOverwrite, extractedOutputs)) {
		result.zipArchivesExtracted++;
		System.out.println("Extracted: " + relative);
	    }
	}
	return extractedOutputs;
    }

    private static boolean extractZipArchive(Path zipPath, Path targetDir, boolean forceOverwrite, Set<Path> extractedOutputs)
	    throws IOException {
	boolean extractedAny = false;
	Files.createDirectories(targetDir);

	try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(zipPath))) {
	    ZipEntry entry;
	    while ((entry = zipInput.getNextEntry()) != null) {
		if (entry.isDirectory()) {
		    Files.createDirectories(targetDir.resolve(entry.getName()));
		    continue;
		}

		Path output = targetDir.resolve(entry.getName()).normalize();
		if (!output.startsWith(targetDir)) {
		    throw new IOException("Zip entry escapes target directory: " + entry.getName());
		}

		if (!forceOverwrite && Files.exists(output)
			&& Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(zipPath)) >= 0) {
		    continue;
		}

		Files.createDirectories(output.getParent());
		Files.copy(zipInput, output, StandardCopyOption.REPLACE_EXISTING);
		extractedOutputs.add(output);
		extractedAny = true;
	    }
	}
	return extractedAny;
    }

    private static void sortObservationCsvs(Path processedRoot, boolean forceOverwrite, Set<Path> extractedOutputs,
	    PrepareResult result) throws IOException {
	Files.walkFileTree(processedRoot, new SimpleFileVisitor<Path>() {
	    @Override
	    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
		if (isObservationCsv(file) && (forceOverwrite || extractedOutputs.contains(file))
			&& sortObservationCsv(file)) {
		    result.observationCsvsSorted++;
		    System.out.println("Sorted: " + processedRoot.relativize(file));
		}
		return FileVisitResult.CONTINUE;
	    }
	});
    }

    private static boolean sortObservationCsv(Path csvPath) throws IOException {
	char delimiter;
	try (BufferedReader headerReader = new BufferedReader(bomAwareReader(csvPath))) {
	    String headerLine = headerReader.readLine();
	    if (headerLine == null) {
		return false;
	    }
	    delimiter = detectDelimiter(headerLine);
	}

	CSVFormat inputFormat = CSVFormat.DEFAULT.builder()
		.setDelimiter(delimiter)
		.setHeader()
		.setSkipHeaderRecord(true)
		.setTrim(true)
		.build();

	List<String> headers = new ArrayList<>();
	List<Map<String, String>> rows = new ArrayList<>();

	try (Reader reader = bomAwareReader(csvPath);
		CSVParser parser = inputFormat.parse(reader)) {
	    headers = new ArrayList<>(parser.getHeaderNames());
	    for (CSVRecord record : parser) {
		Map<String, String> row = new LinkedHashMap<>();
		for (String header : headers) {
		    row.put(header, record.isMapped(header) ? record.get(header) : "");
		}
		rows.add(row);
	    }
	}

	if (rows.isEmpty()) {
	    return false;
	}

	rows.sort(observationRowComparator());

	Path sortedPath = csvPath.resolveSibling(csvPath.getFileName().toString().replaceFirst("(?i)\\.csv$", "-sorted.csv"));
	CSVFormat outputFormat = CSVFormat.DEFAULT.builder()
		.setDelimiter(delimiter)
		.setRecordSeparator(System.lineSeparator())
		.build();

	try (Writer writer = Files.newBufferedWriter(sortedPath, StandardCharsets.UTF_8);
		CSVPrinter printer = new CSVPrinter(writer, outputFormat)) {
	    printer.printRecord(headers);
	    for (Map<String, String> row : rows) {
		List<String> values = new ArrayList<>();
		for (String header : headers) {
		    values.add(row.getOrDefault(header, ""));
		}
		printer.printRecord(values);
	    }
	}

	Files.deleteIfExists(csvPath);
	Files.move(sortedPath, csvPath, StandardCopyOption.REPLACE_EXISTING);
	return true;
    }

    private static Reader bomAwareReader(Path path) throws IOException {
	InputStream input = Files.newInputStream(path);
	PushbackInputStream pushback = new PushbackInputStream(input, 3);
	byte[] bom = new byte[3];
	int read = pushback.read(bom);
	if (read == 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
	    // UTF-8 BOM consumed
	} else if (read > 0) {
	    pushback.unread(bom, 0, read);
	}
	return new InputStreamReader(pushback, StandardCharsets.UTF_8);
    }

    private static char detectDelimiter(String headerLine) {
	int semicolons = countChar(headerLine, ';');
	int commas = countChar(headerLine, ',');
	if (semicolons > commas) {
	    return ';';
	}
	if (headerLine.indexOf('\t') >= 0) {
	    return '\t';
	}
	return ',';
    }

    private static int countChar(String value, char ch) {
	int count = 0;
	for (int i = 0; i < value.length(); i++) {
	    if (value.charAt(i) == ch) {
		count++;
	    }
	}
	return count;
    }

    private static Comparator<Map<String, String>> observationRowComparator() {
	return (left, right) -> {
	    for (String field : SORT_FIELDS) {
		String leftValue = left.getOrDefault(field, "");
		String rightValue = right.getOrDefault(field, "");
		int cmp;
		if (NUMERIC_SORT_FIELDS.contains(field)) {
		    cmp = Integer.compare(parseNumericSortKey(leftValue), parseNumericSortKey(rightValue));
		} else {
		    cmp = leftValue.compareTo(rightValue);
		}
		if (cmp != 0) {
		    return cmp;
		}
	    }
	    return 0;
	};
    }

    private static int parseNumericSortKey(String value) {
	if (value == null || value.isEmpty()) {
	    return Integer.MAX_VALUE;
	}
	try {
	    return Integer.parseInt(value.trim());
	} catch (NumberFormatException e) {
	    return Integer.MAX_VALUE;
	}
    }

    private static boolean isObservationCsv(Path path) {
	String name = path.getFileName().toString().toUpperCase(Locale.ROOT);
	return name.startsWith("OSSERVAZIONI") && name.endsWith(".CSV");
    }

    private static boolean isZip(Path path) {
	return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean shouldSkipPath(Path rawRoot, Path path) {
	if (path.equals(rawRoot)) {
	    return false;
	}
	Path relative = rawRoot.relativize(path);
	for (int i = 0; i < relative.getNameCount(); i++) {
	    String segment = relative.getName(i).toString();
	    if (PROCESSED_DIR.equalsIgnoreCase(segment) || "sta".equalsIgnoreCase(segment)) {
		return true;
	    }
	}
	return false;
    }

    public static final class PrepareResult {
	private int regionalFilesCopied;
	private int zipArchivesExtracted;
	private int observationCsvsSorted;

	public boolean didWork() {
	    return regionalFilesCopied > 0 || zipArchivesExtracted > 0 || observationCsvsSorted > 0;
	}

	public int getRegionalFilesCopied() {
	    return regionalFilesCopied;
	}

	public int getZipArchivesExtracted() {
	    return zipArchivesExtracted;
	}

	public int getObservationCsvsSorted() {
	    return observationCsvsSorted;
	}
    }
}
