package com.spandan.instanthotspot.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.spandan.instanthotspot.core.OnboardingV2

class OnboardingPagerAdapter(
    private val hostActivity: FragmentActivity,
) : FragmentStateAdapter(hostActivity) {
    override fun getItemCount(): Int = OnboardingV2.pageCount(hostActivity)

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OnboardingWelcomeFragment()
            1 -> OnboardingRoleFragment()
            2 -> OnboardingPairingFragment()
            3 -> OnboardingControlsFragment()
            4 -> OnboardingNetworkFragment()
            5 -> OnboardingShortcutsFragment()
            else -> OnboardingWelcomeFragment()
        }
    }
}
