package com.spandan.instanthotspot.main

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.spandan.instanthotspot.R

class MainHostPairFragment : Fragment(R.layout.fragment_main_pair_host)
class MainHostHotspotFragment : Fragment(R.layout.fragment_main_hotspot_host)
class MainHostLogsFragment : Fragment(R.layout.fragment_main_logs_host)
class MainControllerPairFragment : Fragment(R.layout.fragment_main_pair_controller)
class MainControllerHotspotFragment : Fragment(R.layout.fragment_main_hotspot_controller)
class MainControllerLogsFragment : Fragment(R.layout.fragment_main_logs_controller)

class HostMainConsolePagerAdapter(
    activity: FragmentActivity,
) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> MainHostPairFragment()
        1 -> MainHostHotspotFragment()
        2 -> MainHostLogsFragment()
        3 -> MainToolsFragment()
        else -> error("host tab $position")
    }
}

class ControllerMainConsolePagerAdapter(
    activity: FragmentActivity,
) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> MainControllerPairFragment()
        1 -> MainControllerHotspotFragment()
        2 -> MainControllerLogsFragment()
        3 -> MainToolsFragment()
        else -> error("controller tab $position")
    }
}
