package riteshiot.in.smartLid;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatSeekBar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.Transformation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity
{
    private boolean isBTEnabledIndicator = false, isBTRFCommEstablished = false, areUserPreferencesSet = false, sentPreferences =false, sentPreferencesACKReceived = false, received1stStatus = false;
    private volatile boolean isBTCommunicationThreadRunning = false, controlFlowedFromOnStop = false/*, setDeviceNameAndPinOnAppExit = false*/, changeDevicePinOnAppExit = false;
    volatile boolean doubleBackToExitPressedOnce = false;
    Handler handler = new Handler();
    Menu myMenu;

    private TextView txtViewShowContainerDepth, txtViewShowCurrentFilledFluidLevel, txtViewShowBTConnectionStatus;
    private Button btnSetFluidContainerDepthAndThreshold, btnWaterPumpOnOffIndicator;
    private AppCompatSeekBar seekbarSetFluidLevelThresholdPercent;
    private ProgressBar progressBarShowCurrentFluidLevel;
    ProgressDialog initializingOrPreferencesWritingProgressDialog;

    SharedPreferences sharedPreferences;
    SharedPreferences.Editor sharedPreferencesEditor;

    private final String DEFAULT_BT_DEVICE_NAME = "Smart Lid", DEFAULT_BT_DEVICE_ANDROID_ALIAS = "Smart Lid", DEFAULT_BT_DEVICE_PIN = "1899";
    private final UUID BT_CHIP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private String smartLidCurrentBTDeviceName, smartLidCurrentBTDeviceAndroidAlias, smartLidCurrentBTDevicePin;
    private String smartLidNewBTDevicePin;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice btDeviceSmartLid;
    private BluetoothSocket btSocket;
    private BufferedReader brFromDevice;
    private BufferedWriter bwToDevice;
    private bgTaskBTCommunicationThread btCommunicationThread;
    private String inputValues[], prevInputValues[];

    private final BroadcastReceiver activityBTBroadcastReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            final String action = intent.getAction();

            if(action.equals(BluetoothAdapter.ACTION_STATE_CHANGED))
            {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if(state == BluetoothAdapter.STATE_ON)
                {
                    isBTEnabledIndicator = true;
                    new bgTaskQueryPairedDevicesForSmartLidAndEstablishRFComm().execute();
                }else if(state != BluetoothAdapter.STATE_TURNING_ON)
                {
                    Toast.makeText(getApplicationContext(), "Bluetooth NOT Enabled.", Toast.LENGTH_LONG).show();
                    isBTRFCommEstablished = false;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getViews();

        seekbarSetFluidLevelThresholdPercent.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                btnSetFluidContainerDepthAndThreshold.setText((areUserPreferencesSet?"":"Set ")+txtViewShowContainerDepth.getText()+" and "+(15+ seekbarSetFluidLevelThresholdPercent.getProgress())+"% Threshold");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {

            }
        });

        btnSetFluidContainerDepthAndThreshold.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                if(txtViewShowContainerDepth.getText().toString().endsWith("NA") || txtViewShowContainerDepth.getText().toString().matches("\\s+0cm$"))
                    Toast.makeText(getApplicationContext(), "Container Depth not set", Toast.LENGTH_SHORT).show();
                else
                {
                    final int containerDepth = Integer.parseInt(txtViewShowContainerDepth.getText().toString().trim().replaceAll("[^0-9]", ""));
                    if(containerDepth< 28)
                        Toast.makeText(getApplicationContext(), "Container Depth must be greater than 27cm", Toast.LENGTH_SHORT).show();
                    else
                    {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Confirm?")
                                .setMessage("Do you want to store the following values: \n\nContainer Depth: " + containerDepth + "\n\nThresold/Limit percentage below which water pump starts automatically: " + (15 + seekbarSetFluidLevelThresholdPercent.getProgress()) + "%")
                                .setPositiveButton("Yes", new DialogInterface.OnClickListener()
                                {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i)
                                    {
                                        if (btCommunicationThread != null && isBTRFCommEstablished)
                                        {
                                            btCommunicationThread.write(containerDepth + ";" + (15 + seekbarSetFluidLevelThresholdPercent.getProgress()) + ".");
                                            Log.d("App Log SentPreference:", containerDepth + ";" + (15 + seekbarSetFluidLevelThresholdPercent.getProgress()) + ".");
                                            sentPreferences = true;

                                            initializingOrPreferencesWritingProgressDialog.setMessage("Writing Settings to the Device. Please wait");
                                            initializingOrPreferencesWritingProgressDialog.setIndeterminate(true);
                                            initializingOrPreferencesWritingProgressDialog.setCancelable(false);
                                            initializingOrPreferencesWritingProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                                            initializingOrPreferencesWritingProgressDialog.show();
                                        } else
                                            Toast.makeText(getApplicationContext(), "Bluetooth communication not established", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("No", new DialogInterface.OnClickListener()
                                {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i)
                                    {
                                        dialogInterface.dismiss();
                                    }
                                }).show();
                    }
                }
            }
        });

        initViews();

        registerReceiver(activityBTBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        isBluetoothEnabled();
    }

    void isBluetoothEnabled()
    {
        if(mBluetoothAdapter == null)
        {
            isBTEnabledIndicator = isBTRFCommEstablished = false;
            Toast.makeText(this.getApplicationContext(), "Your device has NO Bluetooth capability.", Toast.LENGTH_LONG).show();
        }else if(mBluetoothAdapter.isEnabled())
        {
            isBTEnabledIndicator = true;
            new bgTaskQueryPairedDevicesForSmartLidAndEstablishRFComm().execute();     //found "Smart Lid" as paired device, and its BT MAC Address is stored in smartLidBTDeviceAddress
        }else
            mBluetoothAdapter.enable();
        enableDisableAllFormFields(isBTEnabledIndicator && isBTRFCommEstablished && received1stStatus);
    }

    void getViews()
    {
        txtViewShowContainerDepth = (TextView) findViewById(R.id.txtViewShowContainerDepth);
        seekbarSetFluidLevelThresholdPercent = (AppCompatSeekBar) findViewById(R.id.seekbarSetfluidLevelThresholdPercent);
        btnSetFluidContainerDepthAndThreshold = (Button)findViewById(R.id.btnSetFluidContainerDepthAndThreshold);
        btnWaterPumpOnOffIndicator = (Button) findViewById(R.id.btnWaterPumpOnOffIndicator);
        progressBarShowCurrentFluidLevel = (ProgressBar) findViewById(R.id.progressBarShowCurrentFluidLevel);
        txtViewShowCurrentFilledFluidLevel = (TextView) findViewById(R.id.txtViewShowCurrentFilledFluidLevel);
        txtViewShowBTConnectionStatus = (TextView) findViewById(R.id.txtViewShowBTConnectionStatus);

        initializingOrPreferencesWritingProgressDialog = new ProgressDialog(this);
    }

    void initViews()
    {
        if(sharedPreferences ==null || sharedPreferencesEditor==null)
        {
            sharedPreferences = getApplicationContext().getSharedPreferences("SmartAdapterPreferences", MODE_PRIVATE);
            sharedPreferencesEditor = sharedPreferences.edit();
        }

        txtViewShowContainerDepth.setText("Container Depth: NA");
        btnSetFluidContainerDepthAndThreshold.setText("Set NA Depth and "+(15+ seekbarSetFluidLevelThresholdPercent.getProgress())+"% Threshold");
        progressBarShowCurrentFluidLevel.setProgress(0);
        txtViewShowCurrentFilledFluidLevel.setText(progressBarShowCurrentFluidLevel.getProgress()+"%");

        smartLidCurrentBTDeviceName = sharedPreferences.getString("DeviceName", DEFAULT_BT_DEVICE_NAME);
        smartLidCurrentBTDeviceAndroidAlias = sharedPreferences.getString("DeviceAndroidAlias", DEFAULT_BT_DEVICE_ANDROID_ALIAS);
        smartLidCurrentBTDevicePin = sharedPreferences.getString("DevicePin", DEFAULT_BT_DEVICE_PIN);

        Log.d("App Log DeviceName", smartLidCurrentBTDeviceName);
        Log.d("App Log DeviceAlias", smartLidCurrentBTDeviceAndroidAlias);
        Log.d("App Log DevicePin", smartLidCurrentBTDevicePin);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.menu, menu);
        myMenu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId())
        {
            case R.id.menuItemRetryBTConnection:
                if (Build.VERSION.SDK_INT >= 11)
                    recreate();
                else
                {
                    Intent intent = getIntent();
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    finish();
                    overridePendingTransition(0, 0);

                    startActivity(intent);
                    overridePendingTransition(0, 0);
                }
                break;

            case R.id.menuItemOpenBTSettings:
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                break;

            case R.id.menuItemResetAppSettings:
                new AlertDialog.Builder(this).setTitle("Confirm Reset App Settings?")
                        .setMessage("Are you sure you want to reset Android app's settings to default?"+System.getProperty("line.separator")+"This option clears your aliases and changes PIN to default value.")
                        .setCancelable(true)
                        .setPositiveButton("Yes", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                sharedPreferencesEditor.clear();
                                while(!sharedPreferencesEditor.commit());

                                initViews();

                                Toast.makeText(getApplicationContext(), "Done Resetting Application Settings", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("No", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                dialogInterface.dismiss();
                            }
                        }).create().show();
                break;

            case R.id.menuItemSetDeviceNameNPin:
                /*View dialogLayout = getLayoutInflater().inflate(R.layout.activity_main_dialog_set_name_pin, null);
                final EditText editTextSetDeviceName = (EditText) dialogLayout.findViewById(R.id.editTextSetDeviceName);
                final EditText editTextSetDevicePin = (EditText) dialogLayout.findViewById(R.id.editTextSetDevicePin);

                final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Set Name and/or Pin on App Exit")
                        .setView(dialogLayout)
                        .setMessage("Note: This removes entry from paired devices hence re-pairing is required."+System.getProperty("line.separator")+System.getProperty("line.separator")+"Current Name: "+smartAdapterCurrentBTDeviceName+System.getProperty("line.separator")+"Current Pin: "+smartAdapterCurrentBTDevicePin)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                // overriden the method below after showing dialog because we dont want dismissal of dialog if validation fails
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                dialogInterface.dismiss();
                                finish();
                            }
                        }).create();
                dialog.show();
                dialog.setCancelable(true);

                dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View view)
                    {
                        if(editTextSetDeviceName.getText().length() == 0 && editTextSetDevicePin.getText().length() == 0)
                        {
                            setDeviceNameAndPinOnAppExit = false;
                            Toast.makeText(getApplicationContext(), "Enter value for New Device Name and/or Pin (4 DIGITS)", Toast.LENGTH_SHORT).show();
                        }else if(editTextSetDevicePin.getText().toString().length() > 0 && editTextSetDevicePin.getText().toString().length() != 4)
                        {
                            setDeviceNameAndPinOnAppExit = false;
                            Toast.makeText(getApplicationContext(), "New Device Pin must have 4 digits", Toast.LENGTH_SHORT).show();
                        }else if(editTextSetDeviceName.getText().toString().length()>0 && editTextSetDevicePin.getText().toString().length()>0)
                        {
                            smartAdapterNewBTDeviceName = editTextSetDeviceName.getText().toString();
                            smartAdapterNewBTDevicePin = editTextSetDevicePin.getText().toString();
                            Toast.makeText(getApplicationContext(), "New Device Name: \""+smartAdapterNewBTDeviceName+"\" and Pin: \""+smartAdapterNewBTDevicePin+"\" will be set when you exit the application", Toast.LENGTH_SHORT).show();
                            if (!setDeviceNameAndPinOnAppExit)
                                setDeviceNameAndPinOnAppExit = true;
                            dialog.dismiss();
                        }else if(editTextSetDeviceName.getText().toString().length() > 0 && editTextSetDevicePin.getText().toString().length() == 0)
                        {
                            smartAdapterNewBTDeviceName = editTextSetDeviceName.getText().toString();
                            smartAdapterNewBTDevicePin = smartAdapterCurrentBTDevicePin;
                            Toast.makeText(getApplicationContext(), "New Device Name: \""+smartAdapterNewBTDeviceName+"\" will be set when you exit the application", Toast.LENGTH_SHORT).show();
                            if (!setDeviceNameAndPinOnAppExit)
                                setDeviceNameAndPinOnAppExit = true;
                            dialog.dismiss();
                        }else if(editTextSetDeviceName.getText().toString().length()==0 && editTextSetDevicePin.getText().toString().length() > 0)
                        {
                            smartAdapterNewBTDeviceName = smartAdapterCurrentBTDeviceName;
                            smartAdapterNewBTDevicePin = editTextSetDevicePin.getText().toString();
                            Toast.makeText(getApplicationContext(), "New Device Pin: \""+smartAdapterNewBTDevicePin+"\" will be set when you exit the application", Toast.LENGTH_SHORT).show();
                            if (!setDeviceNameAndPinOnAppExit)
                                setDeviceNameAndPinOnAppExit = true;
                            dialog.dismiss();
                        }
                    }
                });*/
                break;

            case R.id.menuItemGiveAndroidAlias:
                View dialogLayoutGiveAndroidAlias = getLayoutInflater().inflate(R.layout.activity_main_dialog_give_alias, null);
                final EditText editTextGetDeviceAliasName = (EditText) dialogLayoutGiveAndroidAlias.findViewById(R.id.editTextGetDeviceAliasName);

                final AlertDialog dialogGiveAndroidAlias = new AlertDialog.Builder(this).setTitle("Give a local second name to the device")
                        .setMessage("Note: This option just gives a secondary name (Alias for your Android phone) to the device in your Bluetooth settings. Actual device name will still remain: "+smartLidCurrentBTDeviceName+System.getProperty("line.separator")+System.getProperty("line.separator")+"Current Alias: "+smartLidCurrentBTDeviceAndroidAlias+System.getProperty("line.separator"))
                        .setView(dialogLayoutGiveAndroidAlias)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                // overriden the method below after showing dialog because we dont want dismissal of dialog if validation fails
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                dialogInterface.dismiss();
                            }
                        }).create();
                dialogGiveAndroidAlias.show();
                dialogGiveAndroidAlias.setCancelable(true);

                dialogGiveAndroidAlias.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View view)
                    {
                        String btDeviceAndroidAlias = editTextGetDeviceAliasName.getText().toString().trim();
                        if(btDeviceAndroidAlias.length() == 0)
                            Toast.makeText(getApplicationContext(), "Device Alias cannot be left empty", Toast.LENGTH_SHORT).show();
                        else
                        {
                            try
                            {
                                Method method = btDeviceSmartLid.getClass().getMethod("setAlias", String.class);
                                if(method != null)
                                    method.invoke(btDeviceSmartLid, btDeviceAndroidAlias);
                                sharedPreferencesEditor.putString("DeviceAndroidAlias", btDeviceAndroidAlias);
                                while(!sharedPreferencesEditor.commit());
                                Toast.makeText(getApplicationContext(), "Successfully set Alias: \""+btDeviceAndroidAlias+"\" in your Bluetooth Settings.", Toast.LENGTH_SHORT).show();
                            }catch (Exception e)
                            {
                                Toast.makeText(getApplicationContext(), "An error occured while setting alias: \""+btDeviceAndroidAlias+"\". Please try again", Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }
                            dialogGiveAndroidAlias.dismiss();
                        }
                    }
                });
                break;

            case R.id.menuItemChangeDevicePin:
                View dialogLayoutChangeDevicePin = getLayoutInflater().inflate(R.layout.activity_main_dialog_change_pin, null);
                final EditText editTextChangeDevicePin = (EditText) dialogLayoutChangeDevicePin.findViewById(R.id.editTextChangeDevicePin);

                final AlertDialog dialogChangeDevicePin = new AlertDialog.Builder(this).setTitle("Change Bluetooth pairing PIN")
                        .setMessage("Note: This removes entry from paired devices hence re-pairing is required with new PIN. In case PIN assignment fails; you can always press the factory reset button."+System.getProperty("line.separator")+System.getProperty("line.separator")+"Current Pin: "+smartLidCurrentBTDevicePin)
                        .setView(dialogLayoutChangeDevicePin)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                // overriden the method below after showing dialog because we dont want dismissal of dialog if validation fails
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                dialogInterface.dismiss();
                            }
                        }).create();
                dialogChangeDevicePin.show();
                dialogChangeDevicePin.setCancelable(true);

                dialogChangeDevicePin.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View view)
                    {
                        String btDeviceNewPairingPin = editTextChangeDevicePin.getText().toString();
                        if(btDeviceNewPairingPin.length() != 4)
                            Toast.makeText(getApplicationContext(), "Device Pin must be 4 digits", Toast.LENGTH_SHORT).show();
                        else
                        {
                            smartLidNewBTDevicePin = btDeviceNewPairingPin;
                            Toast.makeText(getApplicationContext(), "New Device Pin: \""+smartLidNewBTDevicePin+"\" will be set when you exit the application (deferred until container is not getting filled)", Toast.LENGTH_LONG).show();
                            if (!changeDevicePinOnAppExit)
                                changeDevicePinOnAppExit = true;
                            dialogChangeDevicePin.dismiss();
                        }
                    }
                });
                break;
        }
        return true;
    }

    void enableDisableAllFormFields(boolean isEnabled)
    {
        txtViewShowContainerDepth.setEnabled(isEnabled);
        seekbarSetFluidLevelThresholdPercent.setEnabled(isEnabled);
        btnSetFluidContainerDepthAndThreshold.setEnabled(isEnabled);
        btnWaterPumpOnOffIndicator.setEnabled(isEnabled);
        progressBarShowCurrentFluidLevel.setEnabled(isEnabled);
        txtViewShowCurrentFilledFluidLevel.setEnabled(isEnabled);

        if(myMenu!=null)
        {
            myMenu.findItem(R.id.menuItemRetryBTConnection).setEnabled(!isEnabled);
            myMenu.findItem(R.id.menuItemOpenBTSettings).setEnabled(!isEnabled);
            //myMenu.findItem(R.id.menuItemSetDeviceNameNPin).setEnabled(isEnabled);
            myMenu.findItem(R.id.menuItemChangeDevicePin).setEnabled(isEnabled);
            myMenu.findItem(R.id.menuItemGiveAndroidAlias).setEnabled(isEnabled);
        }
    }

    public class ProgressBarAnimation extends Animation
    {
        private ProgressBar progressBar;
        private float from;
        private float  to;

        public ProgressBarAnimation(ProgressBar progressBar, float from, float to)
        {
            super();
            this.progressBar = progressBar;
            this.from = from;
            this.to = to;
            this.setInterpolator(new LinearInterpolator());
            this.setDuration(500);
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t)
        {
            super.applyTransformation(interpolatedTime, t);
            float value = from + (to - from) * interpolatedTime;
            progressBar.setProgress((int) value);
            txtViewShowCurrentFilledFluidLevel.setText((int)value+"%");
        }
    }

    private class bgTaskBTCommunicationThread extends Thread
    {
        public void run()
        {
            /*this.write("www");
            try
            {
                Thread.sleep(250);
            } catch (InterruptedException ie)
            {
                ie.printStackTrace();
            }
            this.write("ww");*/

            String fromDeviceInputString = "";
            //format:                   areUserPreferenceSet; other values depending on 1st value ..
            //  format values example: 1;current distance in cm;container depth already set in cm;already set threshold %;current fluid level percent;is purifier ON(1/0)
            //  format values example: 0;container depth/current distance in cm

            // Keep listening to the InputStream until an exception occurs
            while (isBTCommunicationThreadRunning)
            {
                try
                {
                    if(brFromDevice==null || bwToDevice==null)
                    {
                        if(btSocket!=null)
                        {
                            brFromDevice = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
                            bwToDevice = new BufferedWriter(new OutputStreamWriter(btSocket.getOutputStream()));
                        }
                    }else
                    {
                        if(prevInputValues == null)
                            Log.d("App Log BTCommThread", "Waiting for 1st status");
                        prevInputValues = inputValues;
                        fromDeviceInputString = brFromDevice.readLine();
                    }

                    if(fromDeviceInputString!=null && fromDeviceInputString.matches("^[\\s\\w;%]+$"))
                    {
                        Log.d("App Log BTInputString", fromDeviceInputString);
                        if(!received1stStatus)
                        {
                            received1stStatus = true;
                            runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    if(initializingOrPreferencesWritingProgressDialog !=null)
                                        initializingOrPreferencesWritingProgressDialog.dismiss();

                                    txtViewShowBTConnectionStatus.setText("Status: Connected");
                                    txtViewShowBTConnectionStatus.setTextColor(Color.GREEN);
                                }
                            });
                        }

                        if(fromDeviceInputString.startsWith("0") && sentPreferences && !sentPreferencesACKReceived && fromDeviceInputString.endsWith("writtenPreferences"))
                        {
                            MainActivity.this.runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    if(initializingOrPreferencesWritingProgressDialog !=null)
                                        initializingOrPreferencesWritingProgressDialog.dismiss();

                                    Toast.makeText(getApplicationContext(), "Successfully stored your settings to the device!", Toast.LENGTH_SHORT).show();
                                }
                            });
                            sentPreferencesACKReceived = true;
                        }else
                        {
                            inputValues = fromDeviceInputString.split(";");

                            areUserPreferencesSet = inputValues[0].equals("1");
                            if((areUserPreferencesSet && inputValues.length == 6) || (!areUserPreferencesSet && (inputValues.length == 2 || inputValues.length == 3)))
                            {
                                MainActivity.this.runOnUiThread(new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        enableDisableAllFormFields(!areUserPreferencesSet);

                                        if (myMenu != null)
                                        {
                                            myMenu.findItem(R.id.menuItemRetryBTConnection).setEnabled(false);
                                            myMenu.findItem(R.id.menuItemOpenBTSettings).setEnabled(false);
                                            //myMenu.findItem(R.id.menuItemSetDeviceNameNPin).setEnabled(isEnabled);
                                            myMenu.findItem(R.id.menuItemChangeDevicePin).setEnabled(false);
                                            myMenu.findItem(R.id.menuItemGiveAndroidAlias).setEnabled(true);
                                        }

                                        try
                                        {
                                            if (areUserPreferencesSet)
                                            {
                                                int progressValue = Integer.valueOf(inputValues[4].replaceAll("[^0-9]", "")), currentDistance = Integer.valueOf(inputValues[1].replaceAll("[^0-9]", "")), containerDepth = Integer.valueOf(inputValues[2].replaceAll("[^0-9]", "")), threshold = Integer.valueOf(inputValues[3].replaceAll("[\\s%]*", ""));
                                                if(containerDepth >= 28)
                                                    txtViewShowContainerDepth.setText("Container Depth: " + containerDepth + " cm");
                                                if(threshold >= 15 && threshold <= 40)
                                                    seekbarSetFluidLevelThresholdPercent.setProgress(threshold - 15);
                                                btnSetFluidContainerDepthAndThreshold.setText((areUserPreferencesSet ? "" : "Set ") + txtViewShowContainerDepth.getText() + " and " + (15 + seekbarSetFluidLevelThresholdPercent.getProgress()) + "% Threshold");
                                                if (progressValue >= 0 && progressValue <= 100 && currentDistance > 0 && currentDistance <= containerDepth)
                                                {
                                                    setProgressBarShowCurrentFluidLevel(progressValue);
                                                    txtViewShowCurrentFilledFluidLevel.setText(inputValues[4]);
                                                    ((GradientDrawable) btnWaterPumpOnOffIndicator.getBackground().getCurrent()).setColor(ContextCompat.getColor(getApplicationContext(), inputValues[5].equals("1") ? R.color.colorWaterPumpONGreen : R.color.colorWaterPumpOFFRed));
                                                }

                                                if (myMenu != null)  // enable changing device pin only when userPreferences are set and only when container is not getting filled
                                                    myMenu.findItem(R.id.menuItemChangeDevicePin).setEnabled(inputValues[5].equals("0"));
                                            } else
                                            {
                                                int containerDepth = Integer.valueOf(inputValues[1].replaceAll("[^0-9]", ""));
                                                if(containerDepth >= 28)
                                                    txtViewShowContainerDepth.setText("Container Depth: " + containerDepth+" cm");
                                                btnSetFluidContainerDepthAndThreshold.setText((areUserPreferencesSet ? "" : "Set ") + txtViewShowContainerDepth.getText() + " and " + (15 + seekbarSetFluidLevelThresholdPercent.getProgress()) + "% Threshold");
                                                setProgressBarShowCurrentFluidLevel(0);
                                                txtViewShowCurrentFilledFluidLevel.setText("0%");
                                                ((GradientDrawable) btnWaterPumpOnOffIndicator.getBackground().getCurrent()).setColor(Color.LTGRAY);
                                            }
                                        }catch (NumberFormatException nfe)
                                        {
                                            //ignore number format exception if half data is passed to Android app (because we are always doing Serial.println and we could establish connection in between of Serail.println then we'll get half data)
                                        }
                                    }
                                });
                            }
                        }
                    }
                    Thread.sleep(400);
                }catch (IOException e)
                {
                    e.printStackTrace();
                    if(e!=null && e.getMessage().contains("socket closed"))
                    {
                        if(!controlFlowedFromOnStop)
                        {
                            runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    Toast.makeText(getApplicationContext(), "Bluetooth Connection Lost", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        if(initializingOrPreferencesWritingProgressDialog!=null)
                            initializingOrPreferencesWritingProgressDialog.dismiss();
                        this.cancel();
                    }
                    break;
                }catch (InterruptedException ie)
                {
                    ie.printStackTrace();
                }
            }
        }

        public void write(String str)
        {
            try
            {
                if(brFromDevice==null || bwToDevice==null)
                {
                    if (btSocket == null)
                    {
                        isBTCommunicationThreadRunning = false;
                        if(btCommunicationThread!=null)
                            btCommunicationThread.cancel();
                    }else
                    {
                        brFromDevice = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
                        bwToDevice = new BufferedWriter(new OutputStreamWriter(btSocket.getOutputStream()));
                    }
                }else
                {
                    bwToDevice.write(str);
                    bwToDevice.flush();
                }
            }catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel()
        {
            try
            {
                isBTCommunicationThreadRunning = false;

                if(bwToDevice!=null)
                {
                    bwToDevice.close();
                    bwToDevice = null;
                }
                if(brFromDevice!=null)
                {
                    brFromDevice.close();
                    brFromDevice = null;
                }
                if(btSocket!=null)
                {
                    btSocket.close();
                    btSocket = null;
                }

                isBTRFCommEstablished = false;
                isBTEnabledIndicator = false;
                received1stStatus = false;
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        enableDisableAllFormFields(isBTEnabledIndicator && isBTRFCommEstablished && received1stStatus);

                        txtViewShowBTConnectionStatus.setTextColor(Color.LTGRAY);
                        txtViewShowBTConnectionStatus.setText("Status: Not Connected");
                    }
                });

            }catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    class bgTaskQueryPairedDevicesForSmartLidAndEstablishRFComm extends AsyncTask<Void, Void, Exception>
    {
        ProgressDialog progressDialog = null;

        @Override
        protected void onPreExecute()
        {
            progressDialog = new ProgressDialog(MainActivity.this);
            progressDialog.setTitle("Searching Smart Lid");
            progressDialog.setMessage("Please wait");
            progressDialog.setCancelable(false);
            progressDialog.setIndeterminate(true);
            progressDialog.show();
        }

        @Override
        protected Exception doInBackground(Void... params)
        {
            try
            {
                if(isBTEnabledIndicator)
                {
                    //check all Aliases 1st to find our Smart Lid (1st preference to Alias name); if not found by Aliases find by device Name: Smart Lid
                    Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
                    if (pairedDevices.size() > 0)
                        for (BluetoothDevice device : pairedDevices)
                        {
                            Log.d("App Log chkPairedAlias", Boolean.toString(device.getClass().getMethod("getAliasName") != null && ((String) device.getClass().getMethod("getAliasName").invoke(device)).equals(smartLidCurrentBTDeviceAndroidAlias)));
                            if (device.getClass().getMethod("getAliasName") != null && ((String) device.getClass().getMethod("getAliasName").invoke(device)).equals(smartLidCurrentBTDeviceAndroidAlias))
                            {
                                btDeviceSmartLid = device;
                                break;
                            }
                        }

                    if(btDeviceSmartLid == null)            //if couldnt find bluetooth device by Alias, try to find using device name: "Smart Lid"
                    {
                        if (pairedDevices.size() > 0)
                            for (BluetoothDevice device : pairedDevices)
                            {
                                Log.d("App Log chkPairedNames", Boolean.toString(device.getName().contains(smartLidCurrentBTDeviceName)) + "for check against "+device.getName());
                                if (device.getName().contains(smartLidCurrentBTDeviceName))
                                {
                                    btDeviceSmartLid = device;
                                    break;
                                }
                            }
                    }else
                        Log.d("App Log AsyncTask", "Found Smart Lid by Alias: "+smartLidCurrentBTDeviceAndroidAlias);

                    if(btDeviceSmartLid ==null)
                    {
                        runOnUiThread(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                Toast.makeText(getApplicationContext(), "Smart Lid (Alias: "+smartLidCurrentBTDeviceAndroidAlias+") NOT paired; opening Bluetooth Settings", Toast.LENGTH_LONG).show();
                                //startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                            }
                        });
                    }else
                    {
                        mBluetoothAdapter.cancelDiscovery();

                        BluetoothSocket tmp = null;
                        try
                        {
                            tmp = btDeviceSmartLid.createRfcommSocketToServiceRecord(BT_CHIP_UUID);
                        }catch (IOException e)
                        {

                        }
                        if(btSocket==null)
                            btSocket = tmp;

                        try
                        {
                            if(!isBTRFCommEstablished && btSocket != null)
                            {
                                btSocket.connect();
                                brFromDevice = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
                                bwToDevice = new BufferedWriter(new OutputStreamWriter(btSocket.getOutputStream()));
                            }
                        }catch (IOException connectionException)
                        {
                            isBTRFCommEstablished = false;

                            try
                            {
                                if(btSocket!=null)
                                    btSocket.close();
                            }catch (IOException e)
                            {

                            }
                        }

                        isBTRFCommEstablished = btSocket!=null && brFromDevice!=null && bwToDevice!=null;
                    }
                }
            }catch(Exception e)
            {
                Log.e("Exception", e.getMessage() == null ? "null" : e.getMessage());
                e.printStackTrace();
                return e;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Exception exceptionOccurred)
        {
            if(progressDialog!=null)
                progressDialog.dismiss();

            if(exceptionOccurred==null)
            {
                enableDisableAllFormFields(isBTEnabledIndicator && isBTRFCommEstablished & received1stStatus);

                if(isBTRFCommEstablished)
                {
                    // already showing a TextView, no need of this Toast
                    // Toast.makeText(getApplicationContext(), "Bluetooth Connection Established", Toast.LENGTH_LONG).show();

                    if(btCommunicationThread==null)
                    {
                        initializingOrPreferencesWritingProgressDialog.setMessage("Initializing");
                        initializingOrPreferencesWritingProgressDialog.setIndeterminate(true);
                        initializingOrPreferencesWritingProgressDialog.setCancelable(false);
                        initializingOrPreferencesWritingProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                        initializingOrPreferencesWritingProgressDialog.show();

                        isBTCommunicationThreadRunning = true;
                        btCommunicationThread = new bgTaskBTCommunicationThread();
                        btCommunicationThread.start();

                        /*if(delayedRerunHandler!=null)
                            delayedRerunHandler.removeCallbacksAndMessages(null);*/
                    }else
                        btCommunicationThread.cancel();
                }else
                {
                    if(btDeviceSmartLid != null)    // it will be null when Device NOT paired so we have already shown toast of "Opening Bluetooth Settings", no need to show this toast again
                        Toast.makeText(getApplicationContext(), "Couldn't establish connection to Smart Lid (Alias: "+smartLidCurrentBTDeviceAndroidAlias+")", Toast.LENGTH_LONG).show();
                    //rerunActivityAfterDelay();
                }
            }else
            {
                Toast.makeText(MainActivity.this, "Error occurred: " + (exceptionOccurred.getMessage() == null ? "No error description found!" : exceptionOccurred.getMessage()), Toast.LENGTH_LONG).show();
                //rerunActivityAfterDelay();
            }
        }
    }
    /*void rerunActivityAfterDelay()
    {
        delayedRerunHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                if (Build.VERSION.SDK_INT >= 11)
                    recreate();
                else
                {
                    Intent intent = getIntent();
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    finish();
                    overridePendingTransition(0, 0);

                    startActivity(intent);
                    overridePendingTransition(0, 0);
                }
            }
        }, 5000);
    }*/

    void setProgressBarShowCurrentFluidLevel(int progress)
    {
        if (android.os.Build.VERSION.SDK_INT >= 11)
            progressBarShowCurrentFluidLevel.startAnimation(new ProgressBarAnimation(progressBarShowCurrentFluidLevel, progressBarShowCurrentFluidLevel.getProgress(), progress));
        else
            progressBarShowCurrentFluidLevel.setProgress(progress); // no animation on Gingerbread or lower
    }

    @Override
    public void onBackPressed()
    {
        if (doubleBackToExitPressedOnce)
        {
            super.onBackPressed();
            return;
        }

        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(getApplicationContext(), "Press back again to exit", Toast.LENGTH_SHORT).show();

        handler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                doubleBackToExitPressedOnce=false;
            }
        }, 2000);
    }

    /*protected void onDestroy()
    {
        super.onDestroy();
        if(delayedRerunHandler!=null)
            delayedRerunHandler.removeCallbacksAndMessages(null);
    }*/

    protected void onStop()
    {
        super.onStop();
        /*if(delayedRerunHandler!=null)
            delayedRerunHandler.removeCallbacksAndMessages(null);*/
        controlFlowedFromOnStop = true;

        /*if(btCommunicationThread!=null)
        {
            if(setDeviceNameAndPinOnAppExit)
                btCommunicationThread.write("~"+smartLidNewBTDevicePin+smartLidNewBTDeviceName+"~");

            // inner while loop in Arduino code will run 5 times for each "q" command definitely ensuring go2sleep becomes true
            // btCommunicationThread.write("qqqqq");

            btCommunicationThread.write("qqq");
            try
            {
                Thread.sleep(320);
            }catch (InterruptedException ie)
            {

            }
            btCommunicationThread.write("qq");

            isBTCommunicationThreadRunning = false;

            if(setDeviceNameAndPinOnAppExit)
            {
                try
                {
                    // unpair Device
                    btDeviceSmartLid.getClass().getMethod("removeBond", (Class[]) null).invoke(btDeviceSmartLid, (Object[]) null);
                }catch(InvocationTargetException ite)
                {
                    ite.printStackTrace();
                }catch (NoSuchMethodException nsme)
                {
                    nsme.printStackTrace();
                }catch(IllegalAccessException iae)
                {
                    iae.printStackTrace();
                }

                sharedPreferencesEditor.putString("DeviceName", smartLidNewBTDeviceName);
                sharedPreferencesEditor.putString("DevicePin", smartLidNewBTDevicePin);
                while(!sharedPreferencesEditor.commit());
            }

            btCommunicationThread.cancel();
        }*/

        if(btCommunicationThread!=null)
        {
            if(changeDevicePinOnAppExit)
                btCommunicationThread.write("p"+smartLidNewBTDevicePin+"p");

            // inner while loop in Arduino code will run 5 times for each "q" command definitely ensuring go2sleep becomes true
            // btCommunicationThread.write("qqqqq");

            btCommunicationThread.write("qqq");
            try
            {
                Thread.sleep(320);
            }catch (InterruptedException ie)
            {

            }
            btCommunicationThread.write("qq");

            isBTCommunicationThreadRunning = false;

            if(changeDevicePinOnAppExit)
            {
                try
                {
                    // unpair Device
                    btDeviceSmartLid.getClass().getMethod("removeBond", (Class[]) null).invoke(btDeviceSmartLid, (Object[]) null);
                }catch(InvocationTargetException ite)
                {
                    ite.printStackTrace();
                }catch (NoSuchMethodException nsme)
                {
                    nsme.printStackTrace();
                }catch(IllegalAccessException iae)
                {
                    iae.printStackTrace();
                }

                sharedPreferencesEditor.putString("DevicePin", smartLidNewBTDevicePin);
                while(!sharedPreferencesEditor.commit());
            }

            btCommunicationThread.cancel();
        }

        unregisterReceiver(activityBTBroadcastReceiver);

        if(handler!=null)
            handler.removeCallbacksAndMessages(null);
    }
    protected void onRestart()
    {
        super.onRestart();
        controlFlowedFromOnStop = false;
        isBluetoothEnabled();
    }
}