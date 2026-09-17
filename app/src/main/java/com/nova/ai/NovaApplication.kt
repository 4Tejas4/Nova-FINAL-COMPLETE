package com.nova.ai

import android.app.Application

class NovaApplication : Application() {
    override fun onCreate() { super.onCreate(); AppContext.init(this) }
}
