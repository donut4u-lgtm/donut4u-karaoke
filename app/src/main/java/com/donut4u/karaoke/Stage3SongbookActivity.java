
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Stage3SongbookActivity extends Activity {

    private static final int BG = Color.rgb(16, 19, 21);
    private static final int PANEL = Color.rgb(35, 39, 42);
    private static final int GREEN = Color.rgb(0, 210, 105);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(185, 194, 189);

    private static final int PICK_FILES = 3101;

    private static final String PREFS = "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";

    private LinearLayout root;
    private LinearLayout songList;
    private EditText searchBox;
    private TextView countText;
    private TextView emptyText;

    private final List<Song> songs = new ArrayList<>();

    private static class Song {
        String number;
        String title;
        String type;
        String uri;

        Song(String number, String title, String type, String uri) {
            this.number = number;
            this.title = title;
            this.type = type;
            this.uri = uri;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        loadSongs();
        buildInterface();
        renderSongs();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        root.setBackgroundColor(BG);

        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("DONUT4U KARAOKE", 25, GREEN, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text(
                "STAGE 3 • SONGBOOK & LOCAL LIBRARY",
                13, MUTED, true
        );
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle, matchWrap());

        Button importButton = button("+ IMPORT SONG FILES");
        root.addView(importButton, fullWidth(dp(52)));
        importButton.setOnClickListener(v -> chooseFiles());

        TextView supported = text(
                "Supported: MIDI, KAR, MP3, LRC, MP4, MKV and WebM",
                12, MUTED, false
        );
        supported.setPadding(dp(4), dp(8), dp(4), dp(16));
        root.addView(supported, matchWrap());

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextColor(WHITE);
        searchBox.setHintTextColor(MUTED);
        searchBox.setHint("Search title, artist or song number");
        searchBox.setTextSize(16);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackgroundColor(PANEL);
        root.addView(searchBox, fullWidth(dp(50)));

        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(
                    CharSequence s, int start, int before, int count) {
                renderSongs();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        countText = text("0 SONGS", 14, GREEN, true);
        countText.setPadding(0, dp(18), 0, dp(10));
        root.addView(countText, matchWrap());

        songList = new LinearLayout(this);
        songList.setOrientation(LinearLayout.VERTICAL);
        root.addView(songList, matchWrap());

        emptyText = text(
                "Your songbook is ready.\n\n"
                        + "Tap IMPORT SONG FILES to add songs from your phone.\n"
                        + "Imported entries will appear here with song numbers.",
                15, MUTED, false
        );
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(12), dp(30), dp(12), dp(30));
        root.addView(emptyText, matchWrap());

        Button backButton = button("BACK");
        LinearLayout.LayoutParams backParams = fullWidth(dp(48));
        backParams.topMargin = dp(20);
        root.addView(backButton, backParams);
        backButton.setOnClickListener(v -> finish());
    }

    private void chooseFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(intent, PICK_FILES);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to open the file picker.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_FILES
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        int added = 0;

        if (data.getClipData() != null) {
            android.content.ClipData clip = data.getClipData();

            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();

                if (importFile(uri)) {
                    added++;
                }
            }
        } else if (data.getData() != null) {
            if (importFile(data.getData())) {
                added++;
            }
        }

        saveSongs();
        renderSongs();

        Toast.makeText(
                this,
                added + " file(s) added to the songbook.",
                Toast.LENGTH_LONG
        ).show();
    }

    private boolean importFile(Uri uri) {
        if (uri == null) {
            return false;
        }

        String uriString = uri.toString();

        for (Song song : songs) {
            if (song.uri.equals(uriString)) {
                return false;
            }
        }

        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;

            getContentResolver().takePersistableUriPermission(
                    uri, flags
            );
        } catch (Exception ignored) {
            // Some document providers do not grant persistent access.
        }

        String filename = getDisplayName(uri);

        if (filename == null || filename.trim().isEmpty()) {
            filename = "Untitled song";
        }

        String extension = getExtension(filename);

        if (!isSupported(extension)) {
            return false;
        }

        String title = removeExtension(filename);
        String number = nextSongNumber();

        songs.add(new Song(number, title, extension.toUpperCase(Locale.ROOT),
                uriString));

        return true;
    }

    private String getDisplayName(Uri uri) {
        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    uri,
                    null,
                    null,
                    null,
                    null
            );

            if (cursor != null
                    && cursor.moveToFirst()) {

                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                );

                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception e) {
            android.util.Log.w(
                    "Donut4UStage3",
                    "Could not read filename",
                    e
            );
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return null;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');

        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }

        return filename.substring(dot + 1)
                .toLowerCase(Locale.ROOT);
    }

    private String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');

        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private boolean isSupported(String extension) {
        return extension.equals("mid")
                || extension.equals("midi")
                || extension.equals("kar")
                || extension.equals("mp3")
                || extension.equals("lrc")
                || extension.equals("mp4")
                || extension.equals("mkv")
                || extension.equals("webm")
                || extension.equals("sf2");
    }

    private String nextSongNumber() {
        int highest = 0;

        for (Song song : songs) {
            try {
                highest = Math.max(
                        highest,
                        Integer.parseInt(song.number)
                );
            } catch (NumberFormatException ignored) {
            }
        }

        return String.format(
                Locale.ROOT, "%05d", highest + 1
        );
    }

    private void renderSongs() {
        if (songList == null || searchBox == null) {
            return;
        }

        songList.removeAllViews();

        String query = searchBox.getText()
                .toString()
                .trim()
                .toLowerCase(Locale.ROOT);

        List<Song> filtered = new ArrayList<>();

        for (Song song : songs) {
            String combined = (
                    song.number + " "
                            + song.title + " "
                            + song.type
            ).toLowerCase(Locale.ROOT);

            if (combined.contains(query)) {
                filtered.add(song);
            }
        }

        Collections.sort(filtered, new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return a.number.compareTo(b.number);
            }
        });

        countText.setText(
                filtered.size() + " SONGS"
                        + (query.isEmpty()
                        ? ""
                        : " FOUND")
        );

        emptyText.setVisibility(
                filtered.isEmpty() ? View.VISIBLE : View.GONE
        );

        if (songs.isEmpty()) {
            emptyText.setText(
                    "Your songbook is ready.\n\n"
                            + "Tap IMPORT SONG FILES to add songs from your phone."
            );
        } else if (filtered.isEmpty()) {
            emptyText.setText("No matching songs. Try another search.");
        }

        for (Song song : filtered) {
            addSongRow(song);
        }
    }

    private void addSongRow(Song song) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(PANEL);

        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = dp(8);
        songList.addView(card, cardParams);

        TextView songTitle = text(
                song.number + "   " + song.title,
                16, WHITE, true
        );
        card.addView(songTitle, matchWrap());

        TextView details = text(
                song.type + " • LOCAL FILE",
                12, MUTED, false
        );
        details.setPadding(0, dp(5), 0, dp(8));
        card.addView(details, matchWrap());

        Button openButton = button("OPEN FILE");
        card.addView(openButton, fullWidth(dp(42)));

        openButton.setOnClickListener(v -> openSong(song));
    }

    private void openSong(Song song) {
        try {
            Uri uri = Uri.parse(song.uri);
            String mime = mimeType(song.type);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(
                    intent, "Open " + song.title
            ));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No compatible app was found to open this file. "
                            + "In-app karaoke playback is the next development step.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String mimeType(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "mid":
            case "midi":
                return "audio/midi";
            case "kar":
                return "audio/midi";
            case "mp3":
                return "audio/mpeg";
            case "lrc":
                return "text/plain";
            case "mp4":
                return "video/mp4";
            case "mkv":
                return "video/x-matroska";
            case "webm":
                return "video/webm";
            case "sf2":
                return "application/octet-stream";
            default:
                return "*/*";
        }
    }

    private void loadSongs() {
        songs.clear();

        SharedPreferences prefs = getSharedPreferences(
                PREFS, MODE_PRIVATE
        );

        String saved = prefs.getString(SONGS_KEY, "[]");

        try {
            JSONArray array = new JSONArray(saved);

            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);

                songs.add(new Song(
                        item.optString("number", ""),
                        item.optString("title", ""),
                        item.optString("type", ""),
                        item.optString("uri", "")
                ));
            }
        } catch (Exception e) {
            android.util.Log.e(
                    "Donut4UStage3",
                    "Could not load songbook",
                    e
            );
        }
    }

    private void saveSongs() {
        JSONArray array = new JSONArray();

        try {
            for (Song song : songs) {
                JSONObject item = new JSONObject();

                item.put("number", song.number);
                item.put("title", song.title);
                item.put("type", song.type);
                item.put("uri", song.uri);

                array.put(item);
            }

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(SONGS_KEY, array.toString())
                    .apply();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not save the songbook.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private TextView text(
            String value, int size, int color, boolean bold) {

        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }

        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(BG);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(GREEN)
        );

        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(height)
        );
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }
}
