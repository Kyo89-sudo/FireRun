package com.kyo89.firerun;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** All'accensione prova ad aprire FireRun (su alcune versioni di Fire OS ci pensa il servizio Home). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Intent home = new Intent(context, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(home);
        } catch (RuntimeException ignored) {
        }
    }
}
