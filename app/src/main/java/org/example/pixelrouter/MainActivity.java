package org.example.pixelrouter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** One-time setup UI. Operational settings are deliberately available in Direct Boot. */
public final class MainActivity extends Activity {
    private Spinner mode;
    private EditText smsTo, mailTo, mailHost, mailFrom, password;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setPadding(32, 24, 32, 24);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        TextView heading = new TextView(this);
        heading.setText("Pixel Router • configurazione locale");
        heading.setTextSize(22);
        column.addView(heading);
        label(column, "Profilo: Casa = Ethernet; Viaggio = hotspot; Auto = hotspot senza Ethernet");
        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {"Casa", "Viaggio", "Auto"}));
        String saved = Config.mode(this);
        mode.setSelection(Config.TRAVEL.equals(saved) ? 1 : Config.AUTO.equals(saved) ? 2 : 0);
        column.addView(mode);
        smsTo = field(column, "Inoltra SMS al numero (+39...); vuoto = disattivato", "sms_to", false);
        mailTo = field(column, "Destinatario e-mail", "mail_to", false);
        mailHost = field(column, "Server SMTP TLS implicito, porta 465", "mail_host", false);
        mailFrom = field(column, "Account SMTP / indirizzo mittente", "mail_from", false);
        password = field(column, "Password app SMTP (vuoto = lascia invariata)", "", true);
        label(column, "Configura prima l'hotspot in Impostazioni Android. Usa un account SMTP dedicato. "
                + "Le credenziali e la coda SMS sono accessibili al sistema prima dello sblocco.");
        Button save = new Button(this);
        save.setText("Salva e avvia");
        save.setOnClickListener(v -> save());
        column.addView(save);
        Button grant = new Button(this);
        grant.setText("Concedi permessi SMS");
        grant.setOnClickListener(v -> requestPermissions(
                new String[] {Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS}, 10));
        column.addView(grant);
        status = new TextView(this);
        column.addView(status);
        Button refresh = new Button(this);
        refresh.setText("Aggiorna stato");
        refresh.setOnClickListener(v -> refresh());
        column.addView(refresh);
        setContentView(scroll);
        refresh();
    }

    private void label(LinearLayout column, String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setPadding(0, 16, 0, 8);
        column.addView(text);
    }

    private EditText field(LinearLayout column, String hint, String key, boolean secret) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        if (!key.isEmpty()) input.setText(Config.get(this, key));
        column.addView(input);
        return input;
    }

    private void save() {
        String[] values = {smsTo.getText().toString().trim(), mailTo.getText().toString().trim(),
                mailHost.getText().toString().trim(), mailFrom.getText().toString().trim()};
        for (String value : values) {
            if (value.contains("\n") || value.contains("\r")) {
                Toast.makeText(this, "Rimuovi i caratteri di nuova riga", Toast.LENGTH_LONG).show();
                return;
            }
        }
        String selected = mode.getSelectedItemPosition() == 1 ? Config.TRAVEL
                : mode.getSelectedItemPosition() == 2 ? Config.AUTO : Config.HOME;
        android.content.SharedPreferences.Editor edit = Config.prefs(this).edit()
                .putString("mode", selected).putString("sms_to", values[0])
                .putString("mail_to", values[1]).putString("mail_host", values[2])
                .putString("mail_from", values[3]);
        if (password.length() > 0) edit.putString("mail_password", password.getText().toString());
        if (!edit.commit()) {
            Toast.makeText(this, "Salvataggio fallito", Toast.LENGTH_LONG).show();
            return;
        }
        password.setText("");
        startService(new Intent(this, RouterService.class));
        Toast.makeText(this, "Configurazione salvata", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void refresh() {
        int[] q = MessageQueue.counts(this);
        status.setText("Stato: " + Config.prefs(this).getString("status", "in attesa")
                + "\nUltimo errore: " + Config.prefs(this).getString("last_error", "nessuno")
                + "\nCoda e-mail: " + q[0] + " • SMS con esito incerto/fallito: " + q[1]
                + " • messaggi conservati: " + q[2]
                + "\nPermessi SMS: " + (checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED ? "concessi" : "mancanti"));
    }
}
