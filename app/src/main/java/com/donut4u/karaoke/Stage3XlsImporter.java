
package com.donut4u.karaoke;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Stage3XlsImporter {

    private Stage3XlsImporter() {
    }

    public static final class Entry {
        public final String number;
        public final String title;
        public final String artist;

        public Entry(String number, String title, String artist) {
            this.number = clean(number);
            this.title = clean(title);
            this.artist = clean(artist);
        }
    }

    public static ArrayList<Entry> read(InputStream input)
            throws Exception {

        if (input == null) {
            throw new IllegalArgumentException(
                    "Cannot read the selected file.");
        }

        byte[] data = readAll(input);

        if (data.length == 0) {
            throw new IllegalArgumentException("The selected file is empty.");
        }

        ArrayList<ArrayList<String>> rows;

        if (isZip(data)) {
            rows = readXlsx(data);
        } else {
            rows = readCsv(data);
        }

        return makeEntries(rows);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;

        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }

        return output.toByteArray();
    }

    private static boolean isZip(byte[] data) {
        return data.length >= 4
                && data[0] == 'P'
                && data[1] == 'K';
    }

    private static ArrayList<ArrayList<String>> readXlsx(byte[] data)
            throws Exception {

        Map<String, byte[]> files = new HashMap<>();

        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(data))) {

            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream();

                    byte[] buffer = new byte[8192];
                    int count;

                    while ((count = zip.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }

                    files.put(entry.getName(), output.toByteArray());
                }

                zip.closeEntry();
            }
        }

        byte[] workbook = files.get("xl/workbook.xml");

        if (workbook == null) {
            throw new IllegalArgumentException(
                    "This is not a supported XLSX workbook. "
                            + "Please select an .xlsx file or CSV file.");
        }

        String relationshipId = firstSheetRelationshipId(workbook);

        String target = firstSheetTarget(
                files.get("xl/_rels/workbook.xml.rels"),
                relationshipId);

        if (target == null || target.isEmpty()) {
            target = "xl/worksheets/sheet1.xml";
        }

        target = normalizeSheetPath(target);

        byte[] sheet = files.get(target);

        if (sheet == null) {
            throw new IllegalArgumentException(
                    "Cannot find the first worksheet in this XLSX file.");
        }

        ArrayList<String> sharedStrings = new ArrayList<>();

        byte[] shared = files.get("xl/sharedStrings.xml");

        if (shared != null) {
            sharedStrings = readSharedStrings(shared);
        }

        return readWorksheet(sheet, sharedStrings);
    }

    private static String firstSheetRelationshipId(byte[] workbook)
            throws Exception {

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(
                new ByteArrayInputStream(workbook), "UTF-8");

        int event;

        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG
                    && "sheet".equals(parser.getName())) {

                String id = parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/"
                                + "officeDocument/2006/relationships",
                        "id");

                if (id == null) {
                    id = parser.getAttributeValue(null, "r:id");
                }

                return id;
            }
        }

        return null;
    }

    private static String firstSheetTarget(
            byte[] relationships,
            String relationshipId) throws Exception {

        if (relationships == null || relationshipId == null) {
            return null;
        }

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(
                new ByteArrayInputStream(relationships), "UTF-8");

        int event;

        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG
                    && "Relationship".equals(parser.getName())) {

                String id = parser.getAttributeValue(null, "Id");

                if (relationshipId.equals(id)) {
                    String type = parser.getAttributeValue(null, "Type");

                    if (type != null
                            && !type.endsWith("/worksheet")) {
                        continue;
                    }

                    return parser.getAttributeValue(null, "Target");
                }
            }
        }

        return null;
    }

    private static String normalizeSheetPath(String target) {
        String path = target.replace('\\', '/');

        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (path.startsWith("xl/")) {
            return path;
        }

        if (path.startsWith("../")) {
            path = path.substring(3);
        }

        return "xl/" + path;
    }

    private static ArrayList<String> readSharedStrings(byte[] xml)
            throws Exception {

        ArrayList<String> result = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml), "UTF-8");

        boolean inItem = false;
        boolean inText = false;
        StringBuilder text = new StringBuilder();

        int event;

        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();

                if ("si".equals(name)) {
                    inItem = true;
                    text.setLength(0);
                } else if (inItem && "t".equals(name)) {
                    inText = true;
                }
            } else if ((event == XmlPullParser.TEXT
                    || event == XmlPullParser.CDSECT) && inText) {
                text.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();

                if ("t".equals(name)) {
                    inText = false;
                } else if ("si".equals(name)) {
                    result.add(text.toString());
                    inItem = false;
                }
            }
        }

        return result;
    }

    private static ArrayList<ArrayList<String>> readWorksheet(
            byte[] xml,
            ArrayList<String> sharedStrings) throws Exception {

        ArrayList<ArrayList<String>> rows = new ArrayList<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml), "UTF-8");

        Map<Integer, String> currentRow = null;
        int currentColumn = -1;
        String cellType = "";
        String value = "";
        StringBuilder inlineText = new StringBuilder();

        boolean inCell = false;
        boolean inValue = false;
        boolean inInlineText = false;

        int event;

        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {

            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();

                if ("row".equals(name)) {
                    currentRow = new HashMap<>();
                } else if ("c".equals(name) && currentRow != null) {
                    inCell = true;
                    value = "";
                    inlineText.setLength(0);

                    String reference =
                            parser.getAttributeValue(null, "r");

                    currentColumn = columnIndex(reference);

                    cellType = parser.getAttributeValue(null, "t");

                    if (cellType == null) {
                        cellType = "";
                    }
                } else if (inCell && "v".equals(name)) {
                    inValue = true;
                } else if (inCell && "t".equals(name)
                        && "inlineStr".equals(cellType)) {
                    inInlineText = true;
                }
            } else if (event == XmlPullParser.TEXT
                    || event == XmlPullParser.CDSECT) {

                if (inValue) {
                    value += parser.getText();
                }

                if (inInlineText) {
                    inlineText.append(parser.getText());
                }

            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();

                if ("v".equals(name)) {
                    inValue = false;
                } else if ("t".equals(name)) {
                    inInlineText = false;
                } else if ("c".equals(name) && inCell) {

                    String cellValue;

                    if ("s".equals(cellType)) {
                        try {
                            int index = Integer.parseInt(value.trim());

                            cellValue = index >= 0
                                    && index < sharedStrings.size()
                                    ? sharedStrings.get(index)
                                    : "";
                        } catch (NumberFormatException ignored) {
                            cellValue = "";
                        }
                    } else if ("inlineStr".equals(cellType)) {
                        cellValue = inlineText.toString();
                    } else {
                        cellValue = value;
                    }

                    if (currentColumn >= 0 && currentRow != null) {
                        currentRow.put(currentColumn, cellValue);
                    }

                    inCell = false;
                } else if ("row".equals(name)
                        && currentRow != null) {

                    rows.add(toOrderedRow(currentRow));
                    currentRow = null;
                }
            }
        }

        return rows;
    }

    private static ArrayList<String> toOrderedRow(
            Map<Integer, String> values) {

        ArrayList<Integer> columns = new ArrayList<>(values.keySet());

        Collections.sort(columns, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Integer.compare(a, b);
            }
        });

        ArrayList<String> row = new ArrayList<>();

        int maxColumn = columns.isEmpty() ? -1
                : columns.get(columns.size() - 1);

        for (int i = 0; i <= maxColumn; i++) {
            row.add(values.containsKey(i) ? values.get(i) : "");
        }

        return row;
    }

    private static int columnIndex(String reference) {
        if (reference == null || reference.isEmpty()) {
            return -1;
        }

        int column = 0;

        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);

            if (c >= 'A' && c <= 'Z') {
                column = column * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                column = column * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }

        return column - 1;
    }

    private static ArrayList<ArrayList<String>> readCsv(byte[] data) {
        String content = new String(data, StandardCharsets.UTF_8);

        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        ArrayList<ArrayList<String>> rows = new ArrayList<>();
        ArrayList<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();

        boolean quoted = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (c == '"') {
                if (quoted && i + 1 < content.length()
                        && content.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r' && i + 1 < content.length()
                        && content.charAt(i + 1) == '\n') {
                    i++;
                }

                row.add(cell.toString());
                cell.setLength(0);

                if (!isEmptyRow(row)) {
                    rows.add(row);
                }

                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }

        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());

            if (!isEmptyRow(row)) {
                rows.add(row);
            }
        }

        return rows;
    }

    private static boolean isEmptyRow(List<String> row) {
        for (String value : row) {
            if (!clean(value).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static ArrayList<Entry> makeEntries(
            ArrayList<ArrayList<String>> rows) {

        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "No rows were found in the selected file.");
        }

        int headerRow = -1;
        int numberColumn = -1;
        int titleColumn = -1;
        int artistColumn = -1;

        int rowsToCheck = Math.min(rows.size(), 10);

        for (int r = 0; r < rowsToCheck; r++) {
            ArrayList<String> row = rows.get(r);

            int foundNumber = -1;
            int foundTitle = -1;
            int foundArtist = -1;

            for (int c = 0; c < row.size(); c++) {
                String normalized = normalize(row.get(c));

                if (isNumberHeader(normalized)) {
                    foundNumber = c;
                } else if (isTitleHeader(normalized)) {
                    foundTitle = c;
                } else if (isArtistHeader(normalized)) {
                    foundArtist = c;
                }
            }

            if (foundTitle >= 0) {
                headerRow = r;
                numberColumn = foundNumber >= 0 ? foundNumber : 0;
                titleColumn = foundTitle;
                artistColumn = foundArtist >= 0 ? foundArtist : 2;
                break;
            }
        }

        int firstDataRow;

        if (headerRow >= 0) {
            firstDataRow = headerRow + 1;
        } else {
            numberColumn = 0;
            titleColumn = 1;
            artistColumn = 2;
            firstDataRow = 0;
        }

        ArrayList<Entry> entries = new ArrayList<>();

        for (int r = firstDataRow; r < rows.size(); r++) {
            ArrayList<String> row = rows.get(r);

            String number = getCell(row, numberColumn);
            String title = getCell(row, titleColumn);
            String artist = getCell(row, artistColumn);

            title = clean(title);

            if (title.isEmpty()) {
                continue;
            }

            number = cleanNumber(number);

            entries.add(new Entry(number, title, artist));
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "No song titles were found. Use columns for "
                            + "song number, title, and artist, or "
                            + "provide a CSV file.");
        }

        return entries;
    }

    private static String getCell(List<String> row, int column) {
        if (column < 0 || column >= row.size()) {
            return "";
        }

        return row.get(column);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static boolean isNumberHeader(String value) {
        return value.equals("no")
                || value.equals("number")
                || value.equals("songno")
                || value.equals("songnumber")
                || value.equals("songcode")
                || value.equals("code")
                || value.equals("trackno")
                || value.equals("tracknumber");
    }

    private static boolean isTitleHeader(String value) {
        return value.equals("title")
                || value.equals("song")
                || value.equals("songtitle")
                || value.equals("songname")
                || value.equals("tracktitle")
                || value.equals("trackname")
                || value.equals("karaoketitle");
    }

    private static boolean isArtistHeader(String value) {
        return value.equals("artist")
                || value.equals("singer")
                || value.equals("songartist")
                || value.equals("artistname")
                || value.equals("performer")
                || value.equals("vocalist");
    }

    private static String cleanNumber(String value) {
        String result = clean(value);

        if (result.matches("\\d+\\.0")) {
            result = result.substring(0, result.length() - 2);
        }

        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
