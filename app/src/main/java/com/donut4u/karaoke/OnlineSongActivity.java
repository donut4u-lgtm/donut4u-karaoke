
private void listenForActiveSession(FirebaseUser user) {
    sessionRef = FirebaseDatabase.getInstance(DATABASE_URL)
            .getReference("activeSessions")
            .child(user.getUid());

    sessionListener = new ValueEventListener() {
        @Override
        public void onDataChange(DataSnapshot snapshot) {
            if (closing || isFinishing()) return;

            String activeId = snapshot.child("sessionId")
                    .getValue(String.class);

            if (activeId == null || activeId.trim().isEmpty()) {
                showSessionError(
                        "No active session found. Please sign in again.");
                return;
            }

            // Allow the authenticated, email-verified user to continue
            // even when the saved installation ID differs.
            // This removes the incorrect automatic sign-out.
            if (!sessionValid) {
                sessionValid = true;
                buildSearchScreen();

                String initialQuery =
                        getIntent().getStringExtra("search_query");

                if (initialQuery != null
                        && !initialQuery.trim().isEmpty()) {
                    searchBox.setText(initialQuery);
                    searchVideos();
                }
            }
        }

        @Override
        public void onCancelled(DatabaseError error) {
            if (closing || isFinishing()) return;

            if (!sessionValid) {
                showSessionError(
                        "Firebase session access failed: "
                                + error.getMessage()
                                + "\nCheck your Realtime Database rules "
                                + "and internet connection.");
            } else {
                toast("Live session check interrupted: "
                        + error.getMessage());
            }
        }
    };

    sessionRef.addValueEventListener(sessionListener);
}
