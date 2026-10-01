package com.devnamdev.iris

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(AutomationPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
