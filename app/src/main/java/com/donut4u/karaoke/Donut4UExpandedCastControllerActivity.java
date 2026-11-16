
package com.donut4u.karaoke;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity;

/**
 * Standard Google Cast expanded media controller.
 *
 * Google Cast manages the receiver connection and media controls.
 */
public final class Donut4UExpandedCastControllerActivity
        extends ExpandedControllerActivity {

    private static final int MENU_CAST =
            0x444F4E01;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        super.onCreateOptionsMenu(menu);

        MenuItem castItem = menu.add(
                Menu.NONE,
                MENU_CAST,
                Menu.NONE,
                "Cast"
        );

        castItem.setShowAsAction(
                MenuItem.SHOW_AS_ACTION_ALWAYS
        );

        CastButtonFactory.setUpMediaRouteButton(
                this,
                menu,
                MENU_CAST
        );

        return true;
    }
}
