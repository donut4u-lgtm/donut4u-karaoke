
package com.donut4u.karaoke;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.BaseAdapter;
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

    private ListView songList;
    private TextView countLabel;
    private SongAdapter adapter;

    private int rawEntries;
    private int invalidEntries;
    private int filteredEntries;
    private boolean preferenceKeyExists;
    private String readStatus = "NOT READ";

    private static final class TvSong {
        final String number;
        final String title;
        final String uri;
        final String type;

        TvSong(String number, String title, String uri, String type) {
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

        rawEntries = 0;
        invalidEntries = 0;
        filteredEntries = 0;
        preferenceKeyExists = false;
        readStatus = "OK";

        SharedPreferences preferences =
                getSharedPreferences(
                        SONGBOOK_PREFS,
                        MODE_PRIVATE
                );

        try {
            preferenceKeyExists =
                    preferences.contains(SONGS_KEY);

            String savedJson =
                    preferences.getString(SONGS_KEY, "[]");

            JSONArray array = new JSONArray(savedJson);
            rawEntries = array.length();

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);

                if (object == null) {
                    invalidEntries++;
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
                    invalidEntries++;
                    continue;
                }

                if (!isPlayableEntry(title, type)) {
                    filteredEntries++;
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
            readStatus = "ERROR: "
                    + e.getClass().getSimpleName();

            android.util.Log.e(
                    "Stage4TV",
                    "Could not read Stage 3 songbook.",
                    e
            );
        }

        android.util.Log.i(
                "Stage4TV",
                "package=" + getPackageName()
                        + " prefs=" + SONGBOOK_PREFS
                        + " key=" + SONGS_KEY
                        + " keyExists=" + preferenceKeyExists
                        + " raw=" + rawEntries
                        + " valid=" + songs.size()
                        + " invalid=" + invalidEntries
                        + " filtered=" + filteredEntries
                        + " status=" + readStatus
        );
    }

    private boolean isPlayableEntry(String title, String type) {
        String safeTitle = title == null
                ? ""
                : title.toLowerCase(Locale.ROOT);

        String safeType = type == null
                ? ""
                : type.toUpperCase(Locale.ROOT);

        boolean catalog =
                "CATALOG".equals(safeType);

        boolean soundFont =
                "SOUNDFONT".equals(safeType)
                        || safeTitle.endsWith(".sf2");

        boolean lyricsFile =
                "LYRICS".equals(safeType)
                        || safeTitle.endsWith(".lrc");

        return !catalog && !soundFont && !lyricsFile;
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(
                dp(54),
                dp(22),
                dp(54),
                dp(20)
        );

        TextView brand = new TextView(this);
        brand.setText("DONUT4U KARAOKE");
        brand.setTextColor(GREEN);
        brand.setTextSize(19);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);

        root.addView(
                brand,
                new LinearLayout.LayoutParams(-1, dp(34))
        );

        TextView heading = new TextView(this);
        heading.setText("CHOOSE YOUR KARAOKE SONG");
        heading.setTextColor(WHITE);
        heading.setTextSize(29);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(8), 0, dp(8));

        root.addView(
                heading,
                new LinearLayout.LayoutParams(-1, dp(58))
        );

        countLabel = new TextView(this);
        countLabel.setTextColor(MUTED);
        countLabel.setTextSize(13);
        countLabel.setGravity(Gravity.CENTER);
        countLabel.setPadding(0, dp(2), 0, dp(12));

        root.addView(
                countLabel,
                new LinearLayout.LayoutParams(-1, dp(82))
        );

        songList = new ListView(this);
        songList.setBackgroundColor(BACKGROUND);
        songList.setDivider(
                new android.graphics.drawable.ColorDrawable(
                        Color.rgb(35, 55, 57)
                )
        );
        songList.setDividerHeight(dp(5));
        songList.setPadding(dp(8), dp(5), dp(8), dp(5));
        songList.setClipToPadding(false);
        songList.setFocusable(true);
        songList.setFocusableInTouchMode(true);
        songList.setItemsCanFocus(false);
        songList.setSelector(createListSelector());

        adapter = new SongAdapter();
        songList.setAdapter(adapter);

        songList.setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position >= 0 && position < songs.size()) {
                        playSong(songs.get(position));
                    }
                }
        );

        root.addView(
                songList,
                new LinearLayout.LayoutParams(-1, 0, 1f)
        );

        TextView instructions = new TextView(this);
        instructions.setText(
                "REMOTE: UP / DOWN to choose  •  OK to play  •  BACK to exit"
        );
        instructions.setTextColor(MUTED);
        instructions.setTextSize(14);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(0, dp(10), 0, dp(4));

        root.addView(
                instructions,
                new LinearLayout.LayoutParams(-1, dp(36))
        );

        setContentView(root);
        updateCount();

        if (!songs.isEmpty()) {
            songList.setSelection(0);
            songList.requestFocus();
        } else {
            songList.setFocusable(false);
            songList.setFocusableInTouchMode(false);
        }
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
        normal.setStroke(dp(1), Color.rgb(40, 65, 65));

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
                        + "   •   RAW SAVED: " + rawEntries
                        + "   •   INVALID: " + invalidEntries
                        + "   •   FILTERED: " + filteredEntries
                        + "\nPREFERENCE KEY EXISTS: "
                        + (preferenceKeyExists ? "YES" : "NO")
                        + "   •   READ: " + readStatus
        );
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
            Stage3SongQueue.initialize(this);

            Stage3SongQueue.setNowPlaying(
                    new Stage3SongQueue.QueuedSong(
                            song.number,
                            song.title,
                            song.uri,
                            song.type
                    )
            );

            startActivity(
                    new Intent(
                            Stage4TvActivity.this,
                            Stage3PlayerActivity.class
                    )
            );

        } catch (Exception e) {
            android.util.Log.e(
                    "Stage4TV",
                    "Could not open Stage 3 player.",
                    e
            );

            Toast.makeText(
                    this,
                    "Could not open the karaoke player.",
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

            if (!songs.isEmpty()
                    && songList != null
                    && songList.getSelectedItemPosition() < 0) {
                songList.setSelection(0);
            }
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
                row = new TextView(Stage4TvActivity.this);
                row.setTextColor(WHITE);
                row.setTextSize(19);
                row.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(22), dp(8), dp(22), dp(8));
                row.setMinHeight(dp(66));
                row.setMaxLines(2);
                row.setEllipsize(
                        android.text.TextUtils.TruncateAt.END
                );
            }

            TvSong song = songs.get(position);

            row.setText(
                    song.number + "   •   "
                            + song.title
                            + "\n"
                            + song.type.toUpperCase(Locale.ROOT)
            );

            return row;
        }
    }
}
