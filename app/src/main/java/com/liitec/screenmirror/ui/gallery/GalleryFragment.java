package com.liitec.screenmirror.ui.gallery;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.liitec.screenmirror.R;
import com.liitec.screenmirror.databinding.FragmentGalleryBinding;

public class GalleryFragment extends Fragment {
    private static final String TAG = "GalleryActivity";
    private static final String PREFERENCE_KEY = "default_config";
    private static final String PREFERENCE_PROTOCOL = "protocol_config";
    private static final String PREFERENCE_SERVER_HOST = "server_host_config";
    private static final String PREFERENCE_SERVER_PASSWORD = "server_password_config";
    private static final String PREFERENCE_SPINNER_FORMAT = "spinner_format_config";
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution_config";
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate_config";



    private Context context;

    private FragmentGalleryBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentGalleryBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        this.context = container.getContext();

        final EditText editTextServerHost = (EditText) root.findViewById(R.id.editText_server_host_Config);
        final EditText editTextServerPassword = (EditText) root.findViewById(R.id.editText_server_password_config);

        final Button saveButton = (Button) root.findViewById(R.id.button_save_config);

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String serverHost = editTextServerHost.getText().toString();
                String serverPassword = editTextServerPassword.getText().toString();

                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString(PREFERENCE_SERVER_HOST, serverHost).apply();
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString(PREFERENCE_SERVER_PASSWORD, serverPassword).apply();
                Toast.makeText(context, "Configuración guardada", Toast.LENGTH_SHORT).show();
            }
        });


        editTextServerHost.setText(context.getSharedPreferences(PREFERENCE_KEY, 0).getString(PREFERENCE_SERVER_HOST, ""));
        editTextServerPassword.setText(context.getSharedPreferences(PREFERENCE_KEY, 0).getString(PREFERENCE_SERVER_PASSWORD, ""));


        setSpinner(R.array.options_protocols, R.array.options_protocols_values,R.id.spinner_protocol_config, PREFERENCE_PROTOCOL, root);
        setSpinner(R.array.options_format_keys,R.array.options_format_values , R.id.spinner_video_format_config, PREFERENCE_SPINNER_FORMAT, root);
        setSpinner(R.array.options_resolution_keys,R.array.options_resolution_values , R.id.spinner_video_resolution_config, PREFERENCE_SPINNER_RESOLUTION, root);
        setSpinner(R.array.options_bitrate_keys,R.array.options_bitrate_values , R.id.spinner_video_bitrate_config, PREFERENCE_SPINNER_BITRATE, root);


        return root;
    }

    private void setSpinner(final int textArrayOptionResId, final int textArrayOptionValues, final int textViewResId, final String preferenceId, View root) {
        Log.d(TAG, "Setting spinner opt_id:" + textArrayOptionResId + " view_id:" + textViewResId + " pref_id:" + preferenceId);

        final Spinner spinner = (Spinner) root.findViewById(textViewResId);

        // Obtener el arreglo de options_format_values
        String[] formatValuesArray = getResources().getStringArray(textArrayOptionValues);

        // Crear un ArrayAdapter con el arreglo obtenido
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(requireContext(), textArrayOptionResId, android.R.layout.simple_spinner_item);

        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Utilizar el valor seleccionado
                String selectedValue = formatValuesArray[position];
                // Realizar las operaciones necesarias con selectedValue
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString(preferenceId, selectedValue).apply();
                // Puedes utilizar el índice (position) si es necesario
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId+"_int", position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString(preferenceId, formatValuesArray[0]).apply();
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId+"_int", 0).apply();
            }
        });

        spinner.setSelection(context.getSharedPreferences(PREFERENCE_KEY, 0).getInt(preferenceId+"_int", 0));
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}