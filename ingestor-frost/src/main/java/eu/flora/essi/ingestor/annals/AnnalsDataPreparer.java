/*
 * Ingestor
 * Copyright (C) 2026 National Research Council of Italy (CNR)/Institute of Technologies and Environmental Intelligence (ITIAm)/ESSI-Lab
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package eu.flora.essi.ingestor.annals;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/**
 * Prepares raw Yearbooks data for ingestion: copies regional CSV files, extracts ZIP
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

    /** Rows kept on the heap for one sorted run. Sized from the max heap so large CSVs spill to disk. */
    private static final int MIN_SORT_CHUNK_ROWS = 2_000;

    private static final int MAX_SORT_CHUNK_ROWS = 100_000;

    /** Object overhead of a parsed row (string array plus column values), used only to size chunks. */
    private static final int ESTIMATED_ROW_BYTES = 2_048;

    /** Open files per merge pass. Further runs are merged in batches so large inputs stay under the fd limit. */
    private static final int MAX_MERGE_FAN_IN = 32;

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
	System.out.println("Preparing Yearbooks data");
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
	return sortObservationCsv(csvPath, sortChunkRows());
    }

    /**
     * Sorts an observation CSV by {@link #SORT_FIELDS}. Rows are sorted in chunks and merged from disk so the
     * whole file is never held on the heap.
     */
    private static boolean sortObservationCsv(Path csvPath, int chunkRows) throws IOException {
	if (chunkRows < 1) {
	    throw new IllegalArgumentException("chunkRows must be positive");
	}

	Path directory = csvPath.toAbsolutePath().getParent();
	if (directory == null) {
	    throw new IOException("Cannot sort a CSV without a parent directory: " + csvPath);
	}

	List<Path> runs = new ArrayList<>();
	Path sortedPath = null;
	try (BufferedReader buffered = new BufferedReader(bomAwareReader(csvPath))) {
	    String headerLine = buffered.readLine();
	    if (headerLine == null || headerLine.isBlank()) {
		return false;
	    }
	    char delimiter = detectDelimiter(headerLine);
	    List<String> headers = parseHeader(headerLine, delimiter);
	    if (headers.isEmpty()) {
		return false;
	    }

	    System.out.println("Sorting: " + csvPath.getFileName() + " (" + formatSize(Files.size(csvPath)) + ", chunk "
		    + chunkRows + " rows)");

	    Comparator<String[]> comparator = observationRowComparator(sortColumnIndexes(headers));
	    int width = headers.size();
	    try (CSVParser parser = bodyFormat(delimiter).parse(buffered)) {
		List<String[]> chunk = new ArrayList<>(Math.min(chunkRows, 10_000));
		for (CSVRecord record : parser) {
		    chunk.add(toValues(record, width));
		    if (chunk.size() >= chunkRows) {
			runs.add(writeSortedRun(chunk, delimiter, directory, comparator));
			if (runs.size() % 10 == 0) {
			    System.out.println("  wrote " + runs.size() + " runs");
			}
			chunk.clear();
		    }
		}
		if (!chunk.isEmpty()) {
		    runs.add(writeSortedRun(chunk, delimiter, directory, comparator));
		}
	    }
	    if (!runs.isEmpty()) {
		System.out.println("  wrote " + runs.size() + " sorted runs");
	    }

	    if (runs.isEmpty()) {
		return false;
	    }

	    sortedPath = Files.createTempFile(directory, ".annals-sorted-", ".csv");
	    externalMerge(runs, sortedPath, headers, delimiter, comparator);
	    runs.clear();
	    replaceFile(sortedPath, csvPath);
	    sortedPath = null;
	    return true;
	} finally {
	    for (Path run : runs) {
		Files.deleteIfExists(run);
	    }
	    if (sortedPath != null) {
		Files.deleteIfExists(sortedPath);
	    }
	}
    }

    private static int sortChunkRows() {
	long budgetBytes = Math.max(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10);
	long rows = budgetBytes / ESTIMATED_ROW_BYTES;
	return (int) Math.max(MIN_SORT_CHUNK_ROWS, Math.min(MAX_SORT_CHUNK_ROWS, rows));
    }

    private static String formatSize(long bytes) {
	if (bytes >= 1024 * 1024) {
	    return (bytes / (1024 * 1024)) + " MiB";
	}
	return Math.max(1, bytes / 1024) + " KiB";
    }

    private static List<String> parseHeader(String headerLine, char delimiter) throws IOException {
	try (CSVParser parser = bodyFormat(delimiter).parse(new StringReader(headerLine))) {
	    Iterator<CSVRecord> records = parser.iterator();
	    if (!records.hasNext()) {
		return List.of();
	    }
	    CSVRecord record = records.next();
	    return new ArrayList<>(Arrays.asList(toValues(record, record.size())));
	}
    }

    private static String[] toValues(CSVRecord record, int width) {
	String[] values = new String[width];
	int limit = Math.min(width, record.size());
	for (int i = 0; i < limit; i++) {
	    String value = record.get(i);
	    values[i] = value == null ? "" : value;
	}
	for (int i = limit; i < width; i++) {
	    values[i] = "";
	}
	return values;
    }

    private static int[] sortColumnIndexes(List<String> headers) {
	int[] indexes = new int[SORT_FIELDS.size()];
	for (int i = 0; i < SORT_FIELDS.size(); i++) {
	    indexes[i] = headers.indexOf(SORT_FIELDS.get(i));
	}
	return indexes;
    }

    private static Path writeSortedRun(List<String[]> rows, char delimiter, Path directory, Comparator<String[]> comparator)
	    throws IOException {
	rows.sort(comparator);
	Path run = Files.createTempFile(directory, ".annals-sort-", ".csv");
	try {
	    try (Writer writer = Files.newBufferedWriter(run, StandardCharsets.UTF_8);
		    CSVPrinter printer = new CSVPrinter(writer, outputFormat(delimiter))) {
		for (String[] row : rows) {
		    printer.printRecord((Object[]) row);
		}
	    }
	    return run;
	} catch (IOException e) {
	    Files.deleteIfExists(run);
	    throw e;
	}
    }

    private static void externalMerge(List<Path> runs, Path output, List<String> headers, char delimiter,
	    Comparator<String[]> comparator) throws IOException {
	List<Path> owned = new ArrayList<>(runs);
	try {
	    List<Path> current = new ArrayList<>(runs);
	    while (current.size() > MAX_MERGE_FAN_IN) {
		System.out.println("  merging " + current.size() + " runs");
		List<Path> next = new ArrayList<>();
		for (int offset = 0; offset < current.size(); offset += MAX_MERGE_FAN_IN) {
		    int end = Math.min(offset + MAX_MERGE_FAN_IN, current.size());
		    List<Path> batch = new ArrayList<>(current.subList(offset, end));
		    if (batch.size() == 1) {
			next.add(batch.get(0));
			continue;
		    }
		    Path merged = Files.createTempFile(output.getParent(), ".annals-merge-", ".csv");
		    owned.add(merged);
		    mergeRuns(batch, merged, null, headers.size(), delimiter, comparator);
		    deleteRuns(batch, owned);
		    next.add(merged);
		}
		current = next;
	    }
	    if (current.size() > 1) {
		System.out.println("  merging " + current.size() + " runs");
	    }
	    mergeRuns(current, output, headers, headers.size(), delimiter, comparator);
	    deleteRuns(current, owned);
	} finally {
	    deleteRuns(owned, null);
	}
    }

    private static void deleteRuns(List<Path> paths, List<Path> owned) throws IOException {
	for (Path path : paths) {
	    Files.deleteIfExists(path);
	    if (owned != null) {
		owned.remove(path);
	    }
	}
    }

    private static void mergeRuns(List<Path> runs, Path output, List<String> headers, int width, char delimiter,
	    Comparator<String[]> comparator) throws IOException {
	List<RunCursor> opened = new ArrayList<>();
	try {
	    PriorityQueue<RunCursor> queue = new PriorityQueue<>(Math.max(1, runs.size()), (left, right) -> {
		int cmp = comparator.compare(left.row, right.row);
		if (cmp != 0) {
		    return cmp;
		}
		return Integer.compare(left.runIndex, right.runIndex);
	    });
	    for (int i = 0; i < runs.size(); i++) {
		RunCursor cursor = RunCursor.open(runs.get(i), delimiter, width, i);
		opened.add(cursor);
		if (cursor.advance()) {
		    queue.add(cursor);
		}
	    }

	    try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
		    CSVPrinter printer = new CSVPrinter(writer, outputFormat(delimiter))) {
		if (headers != null) {
		    printer.printRecord(headers);
		}
		while (!queue.isEmpty()) {
		    RunCursor cursor = queue.poll();
		    printer.printRecord((Object[]) cursor.row);
		    if (cursor.advance()) {
			queue.add(cursor);
		    }
		}
	    }
	} finally {
	    for (RunCursor cursor : opened) {
		cursor.close();
	    }
	}
    }

    private static void replaceFile(Path sortedPath, Path csvPath) throws IOException {
	try {
	    Files.move(sortedPath, csvPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	} catch (AtomicMoveNotSupportedException e) {
	    Files.move(sortedPath, csvPath, StandardCopyOption.REPLACE_EXISTING);
	}
    }

    private static CSVFormat bodyFormat(char delimiter) {
	return CSVFormat.DEFAULT.builder()
		.setDelimiter(delimiter)
		.setTrim(true)
		.build();
    }

    private static CSVFormat outputFormat(char delimiter) {
	return CSVFormat.DEFAULT.builder()
		.setDelimiter(delimiter)
		.setRecordSeparator(System.lineSeparator())
		.build();
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

    private static Comparator<String[]> observationRowComparator(int[] columns) {
	boolean[] numeric = new boolean[columns.length];
	for (int i = 0; i < columns.length; i++) {
	    numeric[i] = NUMERIC_SORT_FIELDS.contains(SORT_FIELDS.get(i));
	}
	return (left, right) -> {
	    for (int i = 0; i < columns.length; i++) {
		int column = columns[i];
		String leftValue = column < 0 || column >= left.length ? "" : left[column];
		String rightValue = column < 0 || column >= right.length ? "" : right[column];
		int cmp = numeric[i]
			? Integer.compare(parseNumericSortKey(leftValue), parseNumericSortKey(rightValue))
			: leftValue.compareTo(rightValue);
		if (cmp != 0) {
		    return cmp;
		}
	    }
	    return 0;
	};
    }

    /**
     * One sorted run opened for a k-way merge. {@code runIndex} keeps the original file order when sort keys tie.
     */
    private static final class RunCursor implements Closeable {
	private final CSVParser parser;
	private final Iterator<CSVRecord> records;
	private final int width;
	private final int runIndex;
	private String[] row;
	private boolean closed;

	private RunCursor(CSVParser parser, int width, int runIndex) {
	    this.parser = parser;
	    this.records = parser.iterator();
	    this.width = width;
	    this.runIndex = runIndex;
	}

	static RunCursor open(Path path, char delimiter, int width, int runIndex) throws IOException {
	    Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
	    try {
		return new RunCursor(bodyFormat(delimiter).parse(reader), width, runIndex);
	    } catch (IOException | RuntimeException e) {
		reader.close();
		throw e;
	    }
	}

	boolean advance() {
	    if (!records.hasNext()) {
		row = null;
		return false;
	    }
	    row = toValues(records.next(), width);
	    return true;
	}

	@Override
	public void close() throws IOException {
	    if (!closed) {
		closed = true;
		parser.close();
	    }
	}
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
