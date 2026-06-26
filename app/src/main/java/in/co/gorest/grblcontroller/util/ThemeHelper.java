/*
 * Copyright (C) 2024-2026 Daniele Cicchinelli
 *
 * Based on GRBLController by zeevy
 * https://github.com/zeevy/grblcontroller
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 * <http://www.gnu.org/licenses/>
 */
package in.co.gorest.grblcontroller.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import in.co.gorest.grblcontroller.R;

/**
 * Central place for the user-selectable colour scheme.
 *
 * The app uses classic AppCompat theming, so a scheme is just a theme that
 * overrides {@code colorPrimary}/{@code colorPrimaryDark}/{@code colorAccent}.
 * Each scheme has two variants (with and without an action bar) to match the
 * two base themes in use; {@link #apply(Activity, boolean)} picks the right one.
 *
 * The HTTP server page reuses the same scheme via {@link #primaryColorRes} et al.
 * so the web interface follows the in-app look.
 */
public final class ThemeHelper {

    public static final String SCHEME_SLATE = "slate";
    public static final String SCHEME_RED   = "red";
    public static final String SCHEME_BLUE  = "blue";
    public static final String SCHEME_TEAL  = "teal";

    private static final String DEFAULT_SCHEME = SCHEME_SLATE;

    private ThemeHelper() {}

    /** Current scheme key, read from the app's named preferences file. */
    public static String getScheme(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ctx.getString(R.string.shared_preference_key), Context.MODE_PRIVATE);
        String s = sp.getString(ctx.getString(R.string.preference_color_scheme), DEFAULT_SCHEME);
        return (s != null && !s.isEmpty()) ? s : DEFAULT_SCHEME;
    }

    /** Applies the scheme theme to an activity. Call before {@code setContentView}. */
    public static void apply(Activity activity, boolean noActionBar) {
        activity.setTheme(themeResFor(getScheme(activity), noActionBar));
    }

    private static int themeResFor(String scheme, boolean noActionBar) {
        if (scheme == null) scheme = DEFAULT_SCHEME;
        switch (scheme) {
            case SCHEME_RED:
                return noActionBar ? R.style.AppTheme_NoActionBar_Red : R.style.AppTheme_Red;
            case SCHEME_BLUE:
                return noActionBar ? R.style.AppTheme_NoActionBar_Blue : R.style.AppTheme_Blue;
            case SCHEME_TEAL:
                return noActionBar ? R.style.AppTheme_NoActionBar_Teal : R.style.AppTheme_Teal;
            case SCHEME_SLATE:
            default:
                return noActionBar ? R.style.AppTheme_NoActionBar_Slate : R.style.AppTheme_Slate;
        }
    }

    // ---- Colour resources for the web palette (kept in sync with the theme) ----

    public static int primaryColorRes(String scheme) {
        if (scheme == null) scheme = DEFAULT_SCHEME;
        switch (scheme) {
            case SCHEME_RED:  return R.color.red_primary;
            case SCHEME_BLUE: return R.color.blue_primary;
            case SCHEME_TEAL: return R.color.teal_primary;
            default:          return R.color.slate_primary;
        }
    }

    public static int primaryDarkColorRes(String scheme) {
        if (scheme == null) scheme = DEFAULT_SCHEME;
        switch (scheme) {
            case SCHEME_RED:  return R.color.red_primary_dark;
            case SCHEME_BLUE: return R.color.blue_primary_dark;
            case SCHEME_TEAL: return R.color.teal_primary_dark;
            default:          return R.color.slate_primary_dark;
        }
    }

    public static int accentColorRes(String scheme) {
        if (scheme == null) scheme = DEFAULT_SCHEME;
        switch (scheme) {
            case SCHEME_RED:  return R.color.red_accent;
            case SCHEME_BLUE: return R.color.blue_accent;
            case SCHEME_TEAL: return R.color.teal_accent;
            default:          return R.color.slate_accent;
        }
    }
}
