/*
 * Copyright (C) 2017 Grbl Controller Contributors
 * Modifications Copyright (C) 2024-2026 Daniele Cicchinelli
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
 *
 * Original project: GRBLController by zeevy
 * https://github.com/zeevy/grblcontroller
 * Modifications by Daniele Cicchinelli, 2024-2026
 */

package in.co.gorest.grblcontroller.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

public class GrblFragmentPagerAdapter extends FragmentPagerAdapter {

    public final int tabCount;

    public GrblFragmentPagerAdapter(FragmentManager fragmentManager, int tabCount) {
        super(fragmentManager);
        this.tabCount = tabCount;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        switch (position) {
            case 1: return FileSenderTabFragment.newInstance();
            case 2: return ProbingTabFragment.newInstance();
            case 3: return ConsoleTabFragment.newInstance();
            case 4: return CamTabFragment.newInstance();
            case 5: return GcodeVisualizerFragment.newInstance(); // Visualizzatore 3D
            case 6: return ZAnalyzerTabFragment.newInstance();    // Analisi Z
            case 7: return GcodeEditorFragment.newInstance();
            default: return JoggingTabFragment.newInstance();
        }
    }

    @Override
    public int getCount() { return tabCount; }
}
