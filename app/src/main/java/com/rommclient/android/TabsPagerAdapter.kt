
package com.rommclient.android

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class TabsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return if (position == 0) {
            PlatformsFragment()
        } else if (position == 1) {
            CollectionsFragment()
        } else {
            LibraryFragment()
        }
    }
}
