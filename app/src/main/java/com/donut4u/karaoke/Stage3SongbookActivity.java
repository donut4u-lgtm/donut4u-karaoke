```java
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

    private String getSelectedSoundFontUri() {
        return preferences.getString(SELECTED_SOUNDFONT_KEY, "");
    }

    private boolean isSelectedSoundFont(Song song) {
        return song != null && song.uri != null
                && song.uri.equals(getSelectedSoundFontUri());
    }

    private void selectSoundFont(Song song) {
        if (song == null || song.uri == null
                || song.uri.trim().isEmpty()) {
            Toast.makeText(this, "This SoundFont has no valid file location.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        preferences.edit()
                .putString(SELECTED_SOUNDFONT_KEY, song.uri)
                .apply();

        renderSongs(searchInput == null
                ? "" : searchInput.getText().toString());

        Toast.makeText(this,
                "Selected SoundFont: " + song.title,
                Toast.LENGTH_LONG).show();
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
            if (isSelectedSoundFont(song)) {
                addNote(card, "CURRENTLY SELECTED FOR MIDI/KAR",
                        Color.rgb(90, 255, 155));
            }
        } else if (lyricsFile) {
            addNote(card, "LYRICS FILE • IMPORT THE MATCHING SONG",
                    Color.rgb(255, 210, 110));
        }

        boolean playable = !catalog && !soundFont && !lyricsFile;

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        if (soundFont) {
            boolean selected = isSelectedSoundFont(song);

            Button selectButton = makeButton(
                    selected ? "SELECTED ✓" : "SELECT FOR MIDI/KAR");
            selectButton.setTextSize(12);
            selectButton.setEnabled(!selected);
            if (selected) {
                selectButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                                Color.rgb(20, 145, 75)));
            }
            selectButton.setOnClickListener(v -> selectSoundFont(song));

            Button removeButton = makeButton("REMOVE");
            removeButton.setTextSize(12);
            removeButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            Color.rgb(170, 45, 45)));

            removeButton.setOnClickListener(v ->
                    new AlertDialog.Builder(
                            Stage3SongbookActivity.this)
                            .setTitle("Remove SoundFont?")
                            .setMessage(
                                    "Remove this SoundFont entry from "
                                            + "the Stage 3 songbook?\n\n"
                                            + "This will not delete the "
                                            + "original file in Downloads "
                                            + "or Google Drive, and will "
                                            + "not delete a cached copy "
                                            + "used by the player.")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("REMOVE",
                                    (dialog, which) -> {
                                        if (isSelectedSoundFont(song)) {
                                            preferences.edit()
                                                    .remove(SELECTED_SOUNDFONT_KEY)
                                                    .apply();
                                        }

                                        songs.remove(song);
                                        saveSongs();

                                        renderSongs(searchInput == null
                                                ? ""
                                                : searchInput.getText()
                                                .toString());

                                        Toast.makeText(
                                                Stage3SongbookActivity.this,
                                                "SoundFont entry removed.",
                                                Toast.LENGTH_SHORT).show();
                                    })
                            .show());

            LinearLayout.LayoutParams half =
                    new LinearLayout.LayoutParams(0, dp(44), 1f);
            half.setMargins(0, 0, dp(6), 0);
            buttons.addView(selectButton, half);
            buttons.addView(removeButton,
                    new LinearLayout.LayoutParams(0, dp(44), 1f));
        } else {
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
        }

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
