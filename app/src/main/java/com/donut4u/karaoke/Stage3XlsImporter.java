
package com.donut4u.karaoke;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

public final class Stage3XlsImporter {

    private Stage3XlsImporter() {
    }

    public static final class Entry {
        public final String number;
        public final String title;
        public final String artist;

        public Entry(String number, String title, String artist) {
            this.number = number == null ? "" : number.trim();
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
        }
    }

    public static ArrayList<Entry> read(InputStream input)
            throws Exception {

        if (input == null) {
            throw new IllegalArgumentException(
                    "Cannot read the selected spreadsheet.");
        }

        ArrayList<Entry> entries = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(input)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException(
                        "The spreadsheet contains no worksheets.");
            }

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);

            FormulaEvaluator evaluator =
                    workbook.getCreationHelper().createFormulaEvaluator();

            int headerRowIndex = -1;
            int numberColumn = -1;
            int titleColumn = -1;
            int artistColumn = -1;

            // Look for column headings in the first 10 rows.
            int rowsToCheck = Math.min(sheet.getLastRowNum() + 1, 10);

            for (int r = 0; r < rowsToCheck; r++) {
                Row row = sheet.getRow(r);

                if (row == null) {
                    continue;
                }

                int rowNumberColumn = -1;
                int rowTitleColumn = -1;
                int rowArtistColumn = -1;

                short lastCell = row.getLastCellNum();

                for (int c = 0; c < lastCell; c++) {
                    String header = cellText(
                            row.getCell(c), formatter, evaluator);

                    String normalized = normalize(header);

                    if (isNumberHeader(normalized)) {
                        rowNumberColumn = c;
                    } else if (isTitleHeader(normalized)) {
                        rowTitleColumn = c;
                    } else if (isArtistHeader(normalized)) {
                        rowArtistColumn = c;
                    }
                }

                if (rowTitleColumn >= 0) {
                    headerRowIndex = r;
                    numberColumn = rowNumberColumn;
                    titleColumn = rowTitleColumn;
                    artistColumn = rowArtistColumn;
                    break;
                }
            }

            int firstDataRow;

            if (headerRowIndex >= 0) {
                firstDataRow = headerRowIndex + 1;

                if (numberColumn < 0) {
                    numberColumn = 0;
                }

                if (artistColumn < 0) {
                    artistColumn = 2;
                }
            } else {
                // No recognizable header:
                // A = song number, B = title, C = artist.
                numberColumn = 0;
                titleColumn = 1;
                artistColumn = 2;
                firstDataRow = 0;
            }

            for (int r = firstDataRow;
                 r <= sheet.getLastRowNum(); r++) {

                Row row = sheet.getRow(r);

                if (row == null) {
                    continue;
                }

                String number = cellText(
                        row.getCell(numberColumn), formatter, evaluator);

                String title = cellText(
                        row.getCell(titleColumn), formatter, evaluator);

                String artist = cellText(
                        row.getCell(artistColumn), formatter, evaluator);

                number = cleanNumber(number);
                title = title.trim();
                artist = artist.trim();

                if (title.isEmpty()) {
                    continue;
                }

                entries.add(new Entry(number, title, artist));
            }
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "No song titles were found. Check the spreadsheet "
                            + "columns and try again.");
        }

        return entries;
    }

    private static String cellText(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return "";
        }

        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (Exception ignored) {
            return formatter.formatCellValue(cell).trim();
        }
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
        if (value == null) {
            return "";
        }

        String result = value.trim();

        if (result.matches("\\d+\\.0")) {
            result = result.substring(0, result.length() - 2);
        }

        return result;
    }
}
