package com.donut4u.karaoke;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class Stage4TvActivity extends Activity {

    private static final String SONGBOOK_PREFS =
            "donut4u_stage3_songbook";

    private static final String SONGS_KEY = "songs_json";

    private static final int REQUEST_IMPORT_TV_SONGS = 4101;

    private static final String EXTRA_NUMBER = "stage4_song_number";
    private static final String EXTRA_TITLE = "stage4_song_title";
    private static final String EXTRA_URI = "stage4_song_uri";
    private static final String EXTRA_TYPE = "stage4_song_type";

    private static final int BACKGROUND =
            Color.rgb(8, 20, 25);

    private static final int PANEL =
            Color.rgb(20, 39, 42);

    private static final int GREEN =
            Color.rgb(0, 220, 115);

    private static final int WHITE =
            Color.rgb(245, 250, 247);

    private static final int MUTED =
            Color.rgb(175, 193, 187);

    private final ArrayList<TvSong> songs =
            new ArrayList<>();

    private SharedPreferences preferences;

    private ListView songList;
    private TextView countLabel;
    private TextView emptyLabel;
    private Button importButton;
    private SongAdapter adapter;

    private int nextNumber = 10000;
    private int totalSavedEntries;

    private static final class TvSong {
        final String number;
        final String title;
        final String uri;
        final String type;

        TvSong(
                String number,
                String title,
                String uri,
                String type
        ) {
            this.number = number;
            this.title = title;
            this.uri = uri;
            this.type = type;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        preferences = getSharedPreferences(
                SONGBOOK_PREFS,
                MODE_PRIVATE
        );

        loadSongs();
        buildScreen();
    }

    private int dp(float value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    private void loadSongs() {
        songs.clear();
        totalSavedEntries = 0;
        nextNumber = 10000;

        try {
            String savedJson =
                    preferences.getString(SONGS_KEY, "[]");

            JSONArray array = new JSONArray(savedJson);

            totalSavedEntries = array.length();

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);

                if (object == null) {
                    continue;
                }

                String number =
                        object.optString("number", "");

                String title =
                        object.optString("title", "");

                String uri =
                        object.optString("uri", "");

                String type =
                        object.optString("type", "AUDIO");

                if (number.trim().isEmpty()
                        || title.trim().isEmpty()
                        || uri.trim().isEmpty()) {
                    continue;
                }

                updateNextNumber(number);

                if (!isPlayableEntry(title, type)) {
                    continue;
                }

                songs.add(new TvSong(
                        number,
                        title,
                        uri,
                        type
                ));
            }

        } catch (Exception e) {
            android.util.Log.e(
                    "Stage4TV",
                    "Could not load TV songbook.",
                    e
            );

            Toast.makeText(
                    this,
                    "Could not read the TV songbook.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateNextNumber(String number) {
        try {
            int value = Integer.parseInt(number);

            if (value >= nextNumber && value < 99999) {
                nextNumber = value + 1;
            }
        } catch (Exception ignored) {
        }
    }

    private String generateNumber() {
        String candidate;

        do {
            candidate = String.format(
                    Locale.US,
                    "%05d",
                    nextNumber++
            );

            if (nextNumber > 99999) {
                nextNumber = 10000;
            }

        } while (numberExists(candidate));

        return candidate;
    }

    private boolean numberExists(String number) {
        try {
            JSONArray array = new JSONArray(
                    preferences.getString(SONGS_KEY, "[]")
            );

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);

                if (object != null
                        && number.equals(
                                object.optString("number", ""))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private boolean isPlayableEntry(
            String title,
            String type
    ) {
        String safeTitle = title == null
                ? ""
                : title.toLowerCase(Locale.ROOT);

        String safeType = type == null
                ? ""
                : type.toUpperCase(Locale.ROOT);

        return !"CATALOG".equals(safeType)
                && !"SOUNDFONT".equals(safeType)
                && !"LYRICS".equals(safeType)
                && !safeTitle.endsWith(".sf2")
                && !safeTitle.endsWith(".lrc");
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(
                dp(54),
                dp(18),
                dp(54),
                dp(16)
        );

        TextView brand = new TextView(this);
        brand.setText("DONUT4U KARAOKE");
        brand.setTextColor(GREEN);
        brand.setTextSize(19);
        brand.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        brand.setGravity(Gravity.CENTER);

        root.addView(
                brand,
                new LinearLayout.LayoutParams(-1, dp(32))
        );

        TextView heading = new TextView(this);
        heading.setText("CHOOSE YOUR KARAOKE SONG");
        heading.setTextColor(WHITE);
        heading.setTextSize(29);
        heading.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(5), 0, dp(5));

        root.addView(
                heading,
                new LinearLayout.LayoutParams(-1, dp(54))
        );

        countLabel = new TextView(this);
        countLabel.setTextColor(MUTED);
        countLabel.setTextSize(15);
        countLabel.setGravity(Gravity.CENTER);
        countLabel.setPadding(0, dp(2), 0, dp(8));

        root.addView(
                countLabel,
                new LinearLayout.LayoutParams(-1, dp(38))
        );

        importButton = new Button(this);
        importButton.setText("IMPORT SONGS FROM THIS TV");
        importButton.setTextSize(17);
        importButton.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        importButton.setTextColor(Color.BLACK);
        importButton.setAllCaps(false);
        importButton.setBackground(createImportBackground());
        importButton.setFocusable(true);
        importButton.setFocusableInTouchMode(true);
        importButton.setOnClickListener(v -> chooseSongsFromTv());

        LinearLayout.LayoutParams importParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(54)
                );

        importParams.bottomMargin = dp(10);

        root.addView(importButton, importParams);

        emptyLabel = new TextView(this);
        emptyLabel.setText(
                "No songs imported on this TV yet.\n"
                        + "Choose IMPORT SONGS FROM THIS TV."
        );
        emptyLabel.setTextColor(MUTED);
        emptyLabel.setTextSize(18);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setPadding(
                dp(20), dp(20), dp(20), dp(20)
        );

        songList = new ListView(this);
        songList.setBackgroundColor(BACKGROUND);
        songList.setDivider(
                new android.graphics.drawable.ColorDrawable(
                        Color.rgb(35, 55, 57)
                )
        );
        songList.setDividerHeight(dp(5));
        songList.setPadding(
                dp(8), dp(5), dp(8), dp(5)
        );
        songList.setClipToPadding(false);
        songList.setFocusable(true);
        songList.setFocusableInTouchMode(true);
        songList.setItemsCanFocus(false);
        songList.setSelector(createListSelector());

        adapter = new SongAdapter();
        songList.setAdapter(adapter);
        songList.setEmptyView(emptyLabel);

        songList.setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position >= 0
                            && position < songs.size()) {
                        playSong(songs.get(position));
                    }
                }
        );

        root.addView(
                songList,
                new LinearLayout.LayoutParams(-1, 0, 1f)
        );

        root.addView(
                emptyLabel,
                new LinearLayout.LayoutParams(-1, 0, 1f)
        );

        TextView instructions = new TextView(this);
        instructions.setText(
                "REMOTE: UP / DOWN to choose  •  OK to select  •  BACK to exit"
        );
        instructions.setTextColor(MUTED);
        instructions.setTextSize(14);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(0, dp(8), 0, 0);

        root.addView(
                instructions,
                new LinearLayout.LayoutParams(-1, dp(30))
        );

        setContentView(root);

        updateCount();

        if (songs.isEmpty()) {
            importButton.requestFocus();
        } else {
            songList.setSelection(0);
            songList.requestFocus();
        }
    }

    private GradientDrawable createImportBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(GREEN);
        background.setCornerRadius(dp(10));
        return background;
    }

    private StateListDrawable createListSelector() {
        StateListDrawable selector = new StateListDrawable();

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(PANEL);
        focused.setCornerRadius(dp(10));
        focused.setStroke(dp(3), GREEN);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.rgb(35, 75, 55));
        pressed.setCornerRadius(dp(10));
        pressed.setStroke(dp(3), GREEN);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.rgb(18, 34, 37));
        normal.setCornerRadius(dp(10));
        normal.setStroke(
                dp(1),
                Color.rgb(40, 65, 65)
        );

        selector.addState(
                new int[]{android.R.attr.state_pressed},
                pressed
        );

        selector.addState(
                new int[]{android.R.attr.state_focused},
                focused
        );

        selector.addState(new int[]{}, normal);

        return selector;
    }

    private void updateCount() {
        if (countLabel == null) {
            return;
        }

        countLabel.setText(
                "AVAILABLE SONGS: " + songs.size()
                        + "   •   SAVED FILES: "
                        + totalSavedEntries
        );
    }

    private void chooseSongsFromTv() {
        Intent intent = new Intent(
                Intent.ACTION_OPEN_DOCUMENT
        );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("*/*");

        intent.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                true
        );

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        try {
            startActivityForResult(
                    intent,
                    REQUEST_IMPORT_TV_SONGS
            );
        } catch (Exception e) {
            android.util.Log.e(
                    "Stage4TV",
                    "Could not open TV file picker.",
                    e
            );

            Toast.makeText(
                    this,
                    "Cannot open the TV file picker.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode != REQUEST_IMPORT_TV_SONGS
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        int imported = 0;
        int skipped = 0;

        ClipData clip = data.getClipData();

        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();

                if (importSong(uri, data.getFlags())) {
                    imported++;
                } else {
                    skipped++;
                }
            }
        } else {
            Uri uri = data.getData();

            if (uri != null) {
                if (importSong(uri, data.getFlags())) {
                    imported++;
                } else {
                    skipped++;
                }
            }
        }

        saveSongs();

        loadSongs();

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        updateCount();

        Toast.makeText(
                this,
                "Imported: " + imported
                        + "   Skipped: " + skipped,
                Toast.LENGTH_LONG
        ).show();

        if (!songs.isEmpty()) {
            songList.setSelection(0);
            songList.requestFocus();
        } else {
            importButton.requestFocus();
        }
    }

    /*
     * Only save a song after Android confirms that read access
     * can be persisted. This prevents dead entries in the songbook.
     */
    private boolean importSong(Uri uri, int flags) {
        if (uri == null) {
            return false;
        }

        String uriString = uri.toString();

        try {
            JSONArray existing = new JSONArray(
                    preferences.getString(SONGS_KEY, "[]")
            );

            for (int i = 0; i < existing.length(); i++) {
                JSONObject object = existing.optJSONObject(i);

                if (object != null
                        && uriString.equals(
                                object.optString("uri", ""))) {
                    return false;
                }
            }
        } catch (Exception ignored) {
        }

        String fileName = getFileName(uri);
        String extension = getExtension(fileName);

        if (!isSupported(extension)) {
            Toast.makeText(
                    this,
                    "Unsupported file: " + fileName,
                    Toast.LENGTH_SHORT
            ).show();
            return false;
        }

        boolean persistableGrant =
                (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;

        int permissionFlags = flags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        boolean hasReadGrant =
                (permissionFlags
                        & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;

        if (!persistableGrant || !hasReadGrant) {
            Toast.makeText(
                    this,
                    "Android did not provide permanent file access: "
                            + fileName,
                    Toast.LENGTH_LONG
            ).show();
            return false;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    permissionFlags
            );
        } catch (Exception e) {
            android.util.Log.w(
                    "Stage4TV",
                    "Could not persist file permission: " + uri,
                    e
            );

            Toast.makeText(
                    this,
                    "Could not save access to: " + fileName,
                    Toast.LENGTH_LONG
            ).show();

            return false;
        }

        boolean savedReadPermission = false;

        for (android.content.UriPermission saved
                : getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(saved.getUri())
                    && saved.isReadPermission()) {
                savedReadPermission = true;
                break;
            }
        }

        if (!savedReadPermission) {
            Toast.makeText(
                    this,
                    "TV could not keep access to: " + fileName,
                    Toast.LENGTH_LONG
            ).show();
            return false;
        }

        try {
            JSONArray array = new JSONArray(
                    preferences.getString(SONGS_KEY, "[]")
            );

            JSONObject object = new JSONObject();
            object.put("number", generateNumber());
            object.put("title", fileName);
            object.put("uri", uriString);
            object.put("type", getType(extension));

            array.put(object);

            boolean saved = preferences.edit()
                    .putString(SONGS_KEY, array.toString())
                    .commit();

            if (!saved) {
                Toast.makeText(
                        this,
                        "Could not save songbook entry: " + fileName,
                        Toast.LENGTH_LONG
                ).show();
            }

            return saved;

        } catch (Exception e) {
            android.util.Log.e(
                    "Stage4TV",
                    "Could not save imported TV song.",
                    e
            );
            return false;
        }
    }

    private void saveSongs() {
        try {
            preferences.edit()
                    .putString(
                            SONGS_KEY,
                            preferences.getString(SONGS_KEY, "[]")
                    )
                    .commit();
        } catch (Exception e) {
            android.util.Log.e(
                    "Stage4TV",
                    "Could not commit TV songbook.",
                    e
            );
        }
    }

    private String getFileName(Uri uri) {
        String name = null;

        try (Cursor cursor = getContentResolver().query(
                uri,
                null,
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                );

                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception e) {
            android.util.Log.w(
                    "Stage4TV",
                    "Could not read file name.",
                    e
            );
        }

        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }

        return name == null || name.trim().isEmpty()
                ? "Untitled song"
                : name;
    }

    private String getExtension(String name) {
        if (name == null) {
            return "";
        }

        int dot = name.lastIndexOf('.');

        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }

        return name.substring(dot + 1)
                .toLowerCase(Locale.ROOT);
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

    private void playSong(TvSong song) {
        if (song == null
                || song.uri == null
                || song.uri.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "This song has no playable media file.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        try {
            Intent intent = new Intent(
                    Stage4TvActivity.this,
                    Stage4TvPlayerActivity.class
            );

            intent.putExtra(EXTRA_NUMBER, song.number);
            intent.putExtra(EXTRA_TITLE, song.title);
            intent.putExtra(EXTRA_URI, song.uri);
            intent.putExtra(EXTRA_TYPE, song.type);

            startActivity(intent);

        } catch (Exception e) {
            android.util.Log.e(
                    "Stage4TV",
                    "Could not open Stage 4 TV player.",
                    e
            );

            Toast.makeText(
                    this,
                    "Could not open the TV karaoke player.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (adapter != null) {
            loadSongs();
            adapter.notifyDataSetChanged();
            updateCount();
        }
    }

    private final class SongAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return songs.size();
        }

        @Override
        public Object getItem(int position) {
            return songs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(
                int position,
                View convertView,
                android.view.ViewGroup parent
        ) {
            TextView row;

            if (convertView instanceof TextView) {
                row = (TextView) convertView;
            } else {
                row = new TextView(
                        Stage4TvActivity.this
                );

                row.setTextColor(WHITE);
                row.setTextSize(19);
                row.setTypeface(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                );
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(
                        dp(22), dp(8), dp(22), dp(8)
                );
                row.setMinHeight(dp(66));
                row.setMaxLines(2);
                row.setEllipsize(
                        android.text.TextUtils.TruncateAt.END
                );
            }

            TvSong song = songs.get(position);

            row.setText(
                    song.number
                            + "   •   "
                            + song.title
                            + "\n"
                            + song.type.toUpperCase(Locale.ROOT)
            );

            return row;
        }
    }
}
