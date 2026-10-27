
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
import java.util.Locale;

public class Stage3SongbookActivity extends Activity {

    private static final String PREFS = "donut4u_stage3_songbook";
    private static final String SONGS_KEY = "songs_json";

    private static final int REQUEST_IMPORT_SONGS = 3103;

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
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
        root.addView(title, new LinearLayout.LayoutParams(
                -1, dp(42)));

        TextView subtitle = new TextView(this);
        subtitle.setText("STAGE 3 • SONGBOOK");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        Button importButton = makeButton("IMPORT SONGS FROM DEVICE");
        importButton.setOnClickListener(v -> chooseSongs());
        root.addView(importButton, buttonParams());

        Button queueButton = makeButton("OPEN SONG QUEUE");
        queueButton.setOnClickListener(v -> {
            Stage3SongQueue.initialize(this);
            try {
                startActivity(new Intent(
                        Stage3SongbookActivity.this,
                        Stage3QueueActivity.class
                ));
            } catch (Exception e) {
                Toast.makeText(this,
                        "Could not open the song queue.",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(queueButton, buttonParams());

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search song number or title");
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(16);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackgroundColor(Color.rgb(35, 48, 40));
        root.addView(searchInput, new LinearLayout.LayoutParams(
                -1, dp(48)));

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

        root.addView(scroll, new LinearLayout.LayoutParams(
                -1, 0, 1f));

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
            public void afterTextChanged(
                    android.text.Editable s) {
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

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_IMPORT_SONGS
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        int imported = 0;

        if (data.getClipData() != null) {
            android.content.ClipData clip = data.getClipData();

            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (importSong(uri, data.getFlags())) {
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
                imported + " song file(s) imported.",
                Toast.LENGTH_LONG).show();
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

            getContentResolver().takePersistableUriPermission(
                    uri, permissionFlags
            );
        } catch (Exception ignored) {
            // Some document providers do not grant persistable access.
        }

        String type = getType(extension);
        String number = generateNumber();

        songs.add(new Song(number, fileName, uriString, type));
        return true;
    }

    private String getFileName(Uri uri) {
        String name = null;
        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    uri,
                    null,
                    null,
                    null,
                    null
            );

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

        if (name == null || name.trim().isEmpty()) {
            name = "Untitled song";
        }

        return name;
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
            candidate = String.format(
                    Locale.US, "%05d", nextNumber);
            nextNumber++;

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

        String saved = preferences.getString(SONGS_KEY, "[]");

        try {
            JSONArray array = new JSONArray(saved);

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);

                String number = object.optString("number", "");
                String title = object.optString("title", "");
                String uri = object.optString("uri", "");
                String type = object.optString("type", "AUDIO");

                if (!number.isEmpty()
                        && !title.isEmpty()
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

        for (Song song : songs) {
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
                "SONGS: " + songs.size() + "   |   SHOWING: " + shown);

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(songs.isEmpty()
                    ? "No songs imported yet.\nTap IMPORT SONGS FROM DEVICE."
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

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button addButton = makeButton("ADD TO QUEUE");
        addButton.setTextSize(12);
        addButton.setOnClickListener(v -> {
            Stage3SongQueue.initialize(this);

            if (Stage3SongQueue.containsUri(song.uri)) {
                Toast.makeText(this,
                        "This song is already in the queue.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            Stage3SongQueue.add(
                    new Stage3SongQueue.QueuedSong(
                            song.number,
                            song.title,
                            song.uri,
                            song.type
                    )
            );

            Toast.makeText(this,
                    "Added " + song.number + " to queue.",
                    Toast.LENGTH_SHORT).show();
        });

        Button openButton = makeButton("OPEN FILE");
        openButton.setTextSize(12);
        openButton.setOnClickListener(v -> openSong(song));

        LinearLayout.LayoutParams half =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        half.setMargins(0, 0, dp(6), 0);
        buttons.addView(addButton, half);

        LinearLayout.LayoutParams otherHalf =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        buttons.addView(openButton, otherHalf);

        card.addView(buttons);
        return card;
    }

    private void openSong(Song song) {
        try {
            Uri uri = Uri.parse(song.uri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(song.title));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Unable to open file")
                    .setMessage(
                            "No compatible app could open this file. " +
                            "The file remains in your songbook.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private String getMimeType(String fileName) {
        String extension = getExtension(fileName);

        switch (extension) {
            case "mid":
            case "midi":
                return "audio/midi";

            case "kar":
                return "audio/midi";

            case "mp3":
                return "audio/mpeg";

            case "wav":
                return "audio/wav";

            case "ogg":
                return "audio/ogg";

            case "m4a":
                return "audio/mp4";

            case "aac":
                return "audio/aac";

            case "mp4":
                return "video/mp4";

            case "mkv":
                return "video/x-matroska";

            case "webm":
                return "video/webm";

            case "lrc":
                return "text/plain";

            case "sf2":
                return "application/octet-stream";

            default:
                return "*/*";
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
