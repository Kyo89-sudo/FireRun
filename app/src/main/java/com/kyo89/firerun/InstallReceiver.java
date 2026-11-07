package com.kyo89.firerun;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Riceve l'esito dell'installazione dell'aggiornamento. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android chiede una conferma sullo schermo: la mostriamo.
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (RuntimeException ignored) {
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(c, "FireRun aggiornato", Toast.LENGTH_SHORT).show();
        } else {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, "Installazione non riuscita" + (msg != null ? ": " + msg : ""),
                    Toast.LENGTH_LONG).show();
        }
    }
}
