
private void searchInternet() {
    String query = searchInput.getText().toString().trim();

    if (query.isEmpty()) {
        searchInput.setError("Enter a song title or artist");
        return;
    }

    hideKeyboard();

    try {
        String encoded = URLEncoder.encode(
                query, StandardCharsets.UTF_8.name());

        selectedSourceUrl =
                "https://www.google.com/search?q=" + encoded;

        selectedAudioUri = null;

        selectedSong.setText(
                "Google search: " + query
                        + "\nChoose a suitable music source, then "
                        + "use OPEN SELECTED SONG WEBSITE.");

        searchStatus.setText(
                "Opening Google Search results...");

        resultsContainer.removeAllViews();

        searchButton.setEnabled(true);

        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(selectedSourceUrl);

        playbackStatus.setText(
                "Google Search is open below. Select a suitable "
                        + "music source. Karaoke conversion works "
                        + "only when the source provides audio "
                        + "the server can access.");

    } catch (Exception e) {
        searchButton.setEnabled(true);
        searchStatus.setText(
                "Unable to open Google Search: "
                        + (e.getMessage() == null
                        ? "Unknown error" : e.getMessage()));
    }
}
