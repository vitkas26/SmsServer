package com.example.smsserver

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import io.ktor.util.InternalAPI

class MyPagerAdapter (fm: FragmentManager): FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT){


    override fun getItem(position: Int): Fragment {
    return when (position){
        0-> {FirstFragment()}
        else-> {SecondFragment()}
    }
    }

    override fun getCount(): Int {
        return 2
    }

    override fun getPageTitle(position: Int): CharSequence? {
        return when (position){
            0 -> "ИСХОДЯЩИЕ"
            else -> "ВХОДЯЩИЕ"
        }
    }
}