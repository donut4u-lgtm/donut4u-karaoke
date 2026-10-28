
package com.donut4u.karaoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

public class Stage3SongbookActivity extends Activity {

    private static final String PREFS = "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";

    private static final int REQUEST_IMPORT_SONGS = 3103;
    private static final int REQUEST_IMPORT_SONGLIST = 3104;
    private static final int REQUEST_IMPORT_SONGHUB_FOLDER = 3105;

    private final ArrayList<Song> songs = new ArrayList<>();

    private LinearLayout songList;
    private EditText searchInput;
    private TextView countLabel;
    private SharedPreferences preferences;
    private int nextNumber = 10000;

    private static class Song {
        String number;
        String title;
        String uri;
        String type;

        Song(String number, String title, String uri, String type) {
            this.number = number;
            this.title = title;
            this.uri = uri;
            this.type = type;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Stage3SongQueue.initialize(this);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        loadSongs();
        buildScreen();
        renderSongs("");
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(10, 20, 15));

        TextView title = new TextView(this);
        title.setText("DONUT4U KARAOKE");
        title.setTextColor(Color.rgb(80, 255, 150));
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • SONGBOOK");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        Button songHubButton = makeButton("CONNECT SONGHUB LIBRARY");
        songHubButton.setOnClickListener(v -> chooseSongHubFolder());
        root.addView(songHubButton, buttonParams());

        Button importButton = makeButton("IMPORT SONGS FROM DEVICE");
        importButton.setOnClickListener(v -> chooseSongs());
        root.addView(importButton, buttonParams());

        Button spreadsheetButton =
                makeButton("IMPORT SONGLIST (XLS / XLSX)");
        spreadsheetButton.setOnClickListener(v -> chooseSongbookFile());
        root.addView(spreadsheetButton, buttonParams());

        Button makeKaraokeButton =
                makeButton("MAKE KARAOKE FILE (AI)");
        makeKaraokeButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(
                        Stage3SongbookActivity.this,
                        Stage3KaraokeMakerActivity.class));
            } catch (Exception e) {
                Toast.makeText(this,
                        "Could not open Karaoke Maker.",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(makeKaraokeButton, buttonParams());

        Button queueButton = makeButton("OPEN SONG QUEUE");
        queueButton.setOnClickListener(v -> {
            try {
                Stage3SongQueue.initialize(this);
                startActivity(new Intent(
                        Stage3SongbookActivity.this,
                        Stage3QueueActivity.class));
            } catch (Exception e) {
                Toast.makeText(this,
                        "Could not open the song queue.",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(queueButton, buttonParams());

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search song number, title or singer");
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(16);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackgroundColor(Color.rgb(35, 48, 40));
        root.addView(searchInput,
                new LinearLayout.LayoutParams(-1, dp(48)));

        countLabel = new TextView(this);
        countLabel.setTextColor(Color.rgb(140, 255, 180));
        countLabel.setTextSize(13);
        countLabel.setPadding(0, dp(10), 0, dp(8));
        root.addView(countLabel);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        songList = new LinearLayout(this);
        songList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(songList);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button backButton = makeButton("BACK");
        backButton.setOnClickListener(v -> finish());
        root.addView(backButton, buttonParams());

        setContentView(root);

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(
                    CharSequence s, int start, int before, int count) {
                renderSongs(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(20, 125, 65)));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(8);
        return params;
    }

    private void chooseSongHubFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(
                    intent, REQUEST_IMPORT_SONGHUB_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this,
                    "Unable to open folder picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        if (requestCode == REQUEST_IMPORT_SONGHUB_FOLDER) {
            Uri treeUri = data.getData();
            if (treeUri == null) {
                return;
            }

            try {
                getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }

            int imported = scanSongHubFolder(treeUri, treeUri);

            saveSongs();
            renderSongs(searchInput == null
                    ? "" : searchInput.getText().toString());

            Toast.makeText(this,
                    imported + " SongHub catalog/media item(s) imported.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (requestCode == REQUEST_IMPORT_SONGLIST) {
            Uri spreadsheet = data.getData();
            if (spreadsheet != null) {
                importSongbookSpreadsheet(spreadsheet);
            }
            return;
        }

        if (requestCode != REQUEST_IMPORT_SONGS) {
            return;
        }

        int imported = 0;

        if (data.getClipData() != null) {
            android.content.ClipData clip = data.getClipData();

            for (int i = 0; i < clip.getItemCount(); i++) {
                if (importSong(clip.getItemAt(i).getUri(), data.getFlags())) {
                    imported++;
                }
            }
        } else if (data.getData() != null) {
            if (importSong(data.getData(), data.getFlags())) {
                imported++;
            }
        }

        saveSongs();
        renderSongs(searchInput == null
                ? "" : searchInput.getText().toString());

        Toast.makeText(this,
                imported + " media file(s) imported.",
                Toast.LENGTH_LONG).show();
    }

    /*
     * Scans the selected tree. When shubidx is found, it looks for
     * shubblob beside that index and stores the blob URI and sector
     * references with each catalog entry.
     */
    private int scanSongHubFolder(Uri treeUri, Uri folderUri) {
        int imported = 0;

        try {
            String documentId =
                    DocumentsContract.getDocumentId(folderUri);

            Uri childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                            treeUri, documentId);

            String[] columns = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };

            try (Cursor cursor = getContentResolver().query(
                    childrenUri, columns, null, null, null)) {

                if (cursor == null) {
                    return 0;
                }

                int idColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);

                if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                    return 0;
                }

                while (cursor.moveToNext()) {
                    String childId = cursor.getString(idColumn);
                    String name = cursor.getString(nameColumn);
                    String mime = cursor.getString(mimeColumn);

                    Uri childUri =
                            DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri, childId);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        imported += scanSongHubFolder(treeUri, childUri);
                        continue;
                    }

                    if (name == null) {
                        continue;
                    }

                    if ("shubidx".equalsIgnoreCase(name)) {
                        Uri blobUri = findSiblingBlob(treeUri, folderUri);
                        imported += importSongHubIndex(childUri, blobUri);

                    } else if (isSupported(getExtension(name))) {
                        if (importSong(childUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                            imported++;
                        }
                    }
                    // shubblob is data storage, not a standalone song.
                }
            }
        } catch (Exception e) {
            android.util.Log.e("Stage3Songbook",
                    "Could not scan folder: " + folderUri, e);
        }

        return imported;
    }

    private Uri findSiblingBlob(Uri treeUri, Uri folderUri) {
        try {
            String documentId =
                    DocumentsContract.getDocumentId(folderUri);

            Uri childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                            treeUri, documentId);

            String[] columns = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };

            try (Cursor cursor = getContentResolver().query(
                    childrenUri, columns, null, null, null)) {

                if (cursor == null) {
                    return null;
                }

                int idColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);

                if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                    return null;
                }

                while (cursor.moveToNext()) {
                    String name = cursor.getString(nameColumn);
                    String mime = cursor.getString(mimeColumn);

                    if (name != null
                            && "shubblob".equalsIgnoreCase(name)
                            && !DocumentsContract.Document.MIME_TYPE_DIR
                            .equals(mime)) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, cursor.getString(idColumn));
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Stage3Songbook",
                    "Could not find sibling shubblob.", e);
        }

        return null;
    }

    private int importSongHubIndex(Uri indexUri, Uri blobUri) {
        int imported = 0;

        try (InputStream input =
                     getContentResolver().openInputStream(indexUri)) {

            if (input == null) {
                throw new Exception("Cannot read shubidx.");
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            ArrayList<Stage3SongHubIndex.Entry> entries =
                    new ArrayList<>(Stage3SongHubIndex.parse(
                            output.toByteArray()));

            if (entries.isEmpty()) {
                Toast.makeText(this,
                        "No catalog records found in shubidx.",
                        Toast.LENGTH_LONG).show();
                return 0;
            }

            for (Stage3SongHubIndex.Entry entry : entries) {
                String displayTitle = entry.title
                        + " — " + entry.artist
                        + " [" + entry.genre + "]";

                String catalogUri;

                if (blobUri != null
                        && entry.blobOffsetSectors >= 0
                        && entry.blobLengthSectors > 0) {

                    /*
                     * Stage3PlayerActivity reads these query parameters.
                     * The blob URI is encoded by Uri.Builder automatically.
                     */
                    catalogUri = new Uri.Builder()
                            .scheme("songhub-midi")
                            .authority("play")
                            .appendQueryParameter(
                                    "blob", blobUri.toString())
                            .appendQueryParameter(
                                    "offset",
                                    String.valueOf(
                                            entry.blobOffsetSectors))
                            .appendQueryParameter(
                                    "length",
                                    String.valueOf(
                                            entry.blobLengthSectors))
                            .build()
                            .toString();

                } else {
                    catalogUri = "songhub-index://catalog/"
                            + Uri.encode(entry.number) + "/"
                            + Uri.encode(entry.title) + "/"
                            + Uri.encode(entry.artist);
                }

                boolean exists = false;
                for (Song song : songs) {
                    if (catalogUri.equals(song.uri)) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    songs.add(new Song(
                            entry.number,
                            displayTitle,
                            catalogUri,
                            blobUri == null ? "CATALOG" : "MIDI/KAR"));
                    imported++;
                }
            }

        } catch (Exception e) {
            android.util.Log.e("Stage3Songbook",
                    "Could not import SongHub index.", e);

            Toast.makeText(this,
                    "SongHub index import failed: "
                            + (e.getMessage() == null
                            ? "Unknown error" : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }

        return imported;
    }

    private void chooseSongs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(intent, REQUEST_IMPORT_SONGS);
        } catch (Exception e) {
            Toast.makeText(this,
                    "Unable to open the file picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chooseSongbookFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
        });
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivityForResult(intent, REQUEST_IMPORT_SONGLIST);
        } catch (Exception e) {
            Toast.makeText(this,
                    "Unable to open the spreadsheet picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void importSongbookSpreadsheet(Uri uri) {
        int imported = 0;
        int duplicates = 0;

        try (InputStream input =
                     getContentResolver().openInputStream(uri)) {

            if (input == null) {
                throw new Exception("Cannot read the selected file.");
            }

            ArrayList<Stage3XlsImporter.Entry> entries =
                    Stage3XlsImporter.read(input);

            for (Stage3XlsImporter.Entry entry : entries) {
                String number = clean(entry.number);
                String title = clean(entry.title);
                String artist = clean(entry.artist);

                if (title.isEmpty()) {
                    continue;
                }

                if (number.isEmpty()) {
                    number = generateNumber();
                }

                if (catalogEntryExists(number, title, artist)) {
                    duplicates++;
                    continue;
                }

                String displayTitle = artist.isEmpty()
                        ? title : title + " — " + artist;

                String catalogUri = "songbook-xls://catalog/"
                        + Uri.encode(number) + "/"
                        + Uri.encode(title) + "/"
                        + Uri.encode(artist);

                songs.add(new Song(
                        number, displayTitle, catalogUri, "CATALOG"));
                imported++;
            }

            saveSongs();
            renderSongs(searchInput.getText().toString());

            new AlertDialog.Builder(this)
                    .setTitle("Songbook import finished")
                    .setMessage("Imported: " + imported
                            + "\nDuplicates skipped: " + duplicates
                            + "\n\nSpreadsheet entries contain song "
                            + "information only, not the actual music.")
                    .setPositiveButton("OK", null)
                    .show();

        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Spreadsheet import failed")
                    .setMessage("Could not read this XLS/XLSX file.\n\n"
                            + (e.getMessage() == null
                            ? "Unknown error" : e.getMessage()))
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean catalogEntryExists(
            String number, String title, String artist) {

        String wantedNumber = clean(number).toLowerCase(Locale.ROOT);
        String wantedTitle = clean(title).toLowerCase(Locale.ROOT);
        String wantedArtist = clean(artist).toLowerCase(Locale.ROOT);

        for (Song song : songs) {
            if (!"CATALOG".equalsIgnoreCase(song.type)) {
                continue;
            }

            if (!song.number.trim().toLowerCase(Locale.ROOT)
                    .equals(wantedNumber)) {
                continue;
            }

            String savedTitle = song.title.trim();
            String savedArtist = "";
            int separator = savedTitle.lastIndexOf(" — ");

            if (separator >= 0) {
                savedArtist = savedTitle.substring(separator + 3).trim();
                savedTitle = savedTitle.substring(0, separator).trim();
            }

            if (savedTitle.toLowerCase(Locale.ROOT).equals(wantedTitle)
                    && savedArtist.toLowerCase(Locale.ROOT)
                    .equals(wantedArtist)) {
                return true;
            }
        }

        return false;
    }

    private boolean importSong(Uri uri, int flags) {
        if (uri == null) {
            return false;
        }

        String uriString = uri.toString();

        for (Song song : songs) {
            if (song.uri.equals(uriString)) {
                return false;
            }
        }

        String fileName = getFileName(uri);
        String extension = getExtension(fileName);

        if (!isSupported(extension)) {
            Toast.makeText(this,
                    "Unsupported file: " + fileName,
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            int permissionFlags = flags
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            if ((permissionFlags
                    & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(
                        uri, permissionFlags);
            }
        } catch (Exception ignored) {
        }

        songs.add(new Song(
                generateNumber(),
                fileName,
                uriString,
                getType(extension)));
        return true;
    }

    private String getFileName(Uri uri) {
        String name = null;
        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    uri, null, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }

        return name == null || name.trim().isEmpty()
                ? "Untitled song" : name;
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isSupported(String extension) {
        switch (extension) {
            case "mid":
            case "midi":
            case "kar":
            case "mp3":
            case "wav":
            case "ogg":
            case "m4a":
            case "aac":
            case "lrc":
            case "mp4":
            case "mkv":
            case "webm":
            case "sf2":
                return true;
            default:
                return false;
        }
    }

    private String getType(String extension) {
        switch (extension) {
            case "mid":
            case "midi":
            case "kar":
                return "MIDI/KAR";
            case "lrc":
                return "LYRICS";
            case "sf2":
                return "SOUNDFONT";
            case "mp4":
            case "mkv":
            case "webm":
                return "VIDEO";
            default:
                return "AUDIO";
        }
    }

    private String generateNumber() {
        String candidate;

        do {
            candidate = String.format(Locale.US, "%05d", nextNumber++);
            if (nextNumber > 99999) {
                nextNumber = 10000;
            }
        } while (numberExists(candidate));

        return candidate;
    }

    private boolean numberExists(String number) {
        for (Song song : songs) {
            if (song.number.equals(number)) {
                return true;
            }
        }
        return false;
    }

    private void loadSongs() {
        songs.clear();

        try {
            JSONArray array = new JSONArray(
                    preferences.getString(SONGS_KEY, "[]"));

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);

                String number = object.optString("number", "");
                String title = object.optString("title", "");
                String uri = object.optString("uri", "");
                String type = object.optString("type", "AUDIO");

                if (!number.isEmpty() && !title.isEmpty()
                        && !uri.isEmpty()) {
                    songs.add(new Song(number, title, uri, type));

                    try {
                        int numeric = Integer.parseInt(number);
                        if (numeric >= nextNumber) {
                            nextNumber = numeric + 1;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Toast.makeText(this,
                    "The saved songbook could not be read.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveSongs() {
        JSONArray array = new JSONArray();

        try {
            for (Song song : songs) {
                JSONObject object = new JSONObject();
                object.put("number", song.number);
                object.put("title", song.title);
                object.put("uri", song.uri);
                object.put("type", song.type);
                array.put(object);
            }

            preferences.edit()
                    .putString(SONGS_KEY, array.toString())
                    .apply();

        } catch (Exception e) {
            Toast.makeText(this,
                    "Could not save the songbook.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void renderSongs(String query) {
        if (songList == null || countLabel == null) {
            return;
        }

        songList.removeAllViews();

        String search = query == null
                ? "" : query.trim().toLowerCase(Locale.ROOT);

        int shown = 0;
        int catalogOnly = 0;

        for (Song song : songs) {
            if ("CATALOG".equalsIgnoreCase(song.type)) {
                catalogOnly++;
            }

            String searchable = (
                    song.number + " " + song.title + " " + song.type
            ).toLowerCase(Locale.ROOT);

            if (!search.isEmpty() && !searchable.contains(search)) {
                continue;
            }

            shown++;
            songList.addView(createSongCard(song));
        }

        countLabel.setText(
                "ENTRIES: " + songs.size()
                        + "  |  SHOWING: " + shown
                        + "  |  INFORMATION ONLY: " + catalogOnly);

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(songs.isEmpty()
                    ? "No songs imported yet."
                    : "No matching songs found.");
            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            songList.addView(empty);
        }
    }

    private View createSongCard(Song song) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(Color.rgb(25, 39, 30));

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        TextView number = new TextView(this);
        number.setText(song.number + "  •  " + song.type);
        number.setTextColor(Color.rgb(90, 255, 155));
        number.setTextSize(14);
        number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(number);

        TextView title = new TextView(this);
        title.setText(song.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setPadding(0, dp(4), 0, dp(8));
        card.addView(title);

        boolean catalog = "CATALOG".equalsIgnoreCase(song.type);
        boolean soundFont = "SOUNDFONT".equalsIgnoreCase(song.type)
                || song.title.toLowerCase(Locale.ROOT).endsWith(".sf2");
        boolean lyricsFile = "LYRICS".equalsIgnoreCase(song.type)
                || song.title.toLowerCase(Locale.ROOT).endsWith(".lrc");

        if (catalog) {
            addNote(card, "SONG INFORMATION ONLY • NO MEDIA LINK",
                    Color.rgb(255, 210, 110));
        } else if (soundFont) {
            addNote(card, "SOUNDFONT • MIDI/KAR INSTRUMENT BANK",
                    Color.rgb(140, 255, 180));
        } else if (lyricsFile) {
            addNote(card, "LYRICS FILE • IMPORT THE MATCHING SONG",
                    Color.rgb(255, 210, 110));
        }

        boolean playable = !catalog && !soundFont && !lyricsFile;

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button addButton = makeButton("ADD TO QUEUE");
        addButton.setTextSize(12);
        addButton.setEnabled(playable);

        addButton.setOnClickListener(v -> {
            if (!playable) {
                showCatalogNotice();
                return;
            }

            Stage3SongQueue.initialize(this);

            if (Stage3SongQueue.containsUri(song.uri)) {
                Toast.makeText(this,
                        "This song is already in the queue.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            boolean added = Stage3SongQueue.add(
                    new Stage3SongQueue.QueuedSong(
                            song.number, song.title, song.uri, song.type));

            Toast.makeText(this,
                    added ? "Added " + song.number + " to queue."
                            : "Could not add this song.",
                    Toast.LENGTH_SHORT).show();
        });

        Button openButton = makeButton(
                catalog ? "SONG INFO"
                        : soundFont ? "SOUNDFONT"
                        : lyricsFile ? "LYRICS FILE"
                        : "PLAY IN DONUT4U");

        openButton.setTextSize(12);
        openButton.setEnabled(playable);
        openButton.setOnClickListener(v -> {
            if (!playable) {
                showCatalogNotice();
                return;
            }
            playInDonutStage(song);
        });

        LinearLayout.LayoutParams half =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        half.setMargins(0, 0, dp(6), 0);
        buttons.addView(addButton, half);

        buttons.addView(openButton,
                new LinearLayout.LayoutParams(0, dp(44), 1f));

        card.addView(buttons);
        return card;
    }

    private void addNote(LinearLayout card, String text, int color) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextColor(color);
        note.setTextSize(11);
        note.setPadding(0, 0, 0, dp(8));
        card.addView(note);
    }

    private void showCatalogNotice() {
        new AlertDialog.Builder(this)
                .setTitle("Songbook entry")
                .setMessage(
                        "This entry contains song information only. "
                                + "Import a matching playable media file. "
                                + "SoundFonts are instruments for MIDI/KAR; "
                                + "LRC files contain lyrics.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void playInDonutStage(Song song) {
        if (song == null || song.uri == null
                || song.uri.trim().isEmpty()) {
            Toast.makeText(this,
                    "This song has no playable media file.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Stage3SongQueue.initialize(this);
            Stage3SongQueue.setNowPlaying(
                    new Stage3SongQueue.QueuedSong(
                            song.number, song.title, song.uri, song.type));

            startActivity(new Intent(
                    Stage3SongbookActivity.this,
                    Stage3PlayerActivity.class));

        } catch (Exception e) {
            android.util.Log.e("Stage3Songbook",
                    "Could not launch built-in player.", e);

            Toast.makeText(this,
                    "Could not open Donut4U Stage 3.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (songList != null && searchInput != null) {
            Stage3SongQueue.initialize(this);
            renderSongs(searchInput.getText().toString());
        }
    }
}
