/*      Author:                     Ritesh Talreja
 *      Company:                    Unify
 *      Date of last Code Change:   1Dec2016
 *      Product Description:        Smart Lid for Fluid/Water Storage Containers
 *
*/

#include <NewPing.h>
#include <EEPROM.h>
//#include <MsTimer2.h>
#include <avr/sleep.h>

#define ARDUINO_TX_BT_RX_OPIN 0                       // Arduino serial port Trasmit (TX) pin 0 connected to Bluetooth module's Receive (RX) pin
#define ARDUINO_RX_BT_TX_IPIN 1                       // Arduino serial port Receive (RX) pin 0 connected to Bluetooth module's Transmit (TX) pin
#define FACTORY_RESET_BTN_IPIN 2                      // Arduino pin tied to Push button switch to clear EEPROM and reset the arduino
#define LDR_IPIN 3                                    // Arduino pin tied to Push button switch to clear EEPROM and reset the arduino
#define TRIGGER_OPIN 4                                // Arduino pin tied to trigger pin on the ultrasonic sensor.
#define ECHO_IPIN 5                                   // Arduino pin tied to trigger pin on the ultrasonic sensor.
#define AC_RELAY_OPIN 6                               // Arduino pin tied to AC RELAY (connected to water pump) pin (Dolphin Solid State Relay 24-480VAC 5A 3-32VDC 4-16mA HIGH LEVEL TRIGGER)
#define MEWC_ON_OPIN 7                               // Arduino pin tied to AC RELAY (connected to water pump) pin (Dolphin Solid State Relay 24-480VAC 5A 3-32VDC 4-16mA HIGH LEVEL TRIGGER)

#define BT_PIN_SIZE 4
#define MAX_CONTAINER_DEPTH_POSITIVE_TOLERANCE 9         // max depth + 1 to max depth + 9cm is considered as max depth value for SONAR
#define MAX_DISTANCE 450                                 // Maximum distance we want to ping for (in centimeters). Maximum sensor distance is rated at 400-500cm.
boolean areUserPreferencesSet = false;
volatile boolean isACRelayOn = false;
NewPing sonar(TRIGGER_OPIN, ECHO_IPIN, MAX_DISTANCE);       // NewPing setup of pins and maximum distance.

volatile unsigned int prev_prev_distance = 0;                          // previous current distance between fluid/obstacle and sensor
volatile unsigned int prev_distance = 0;                          // previous current distance between fluid/obstacle and sensor
volatile unsigned int current_distance = 0;                          // current distance between fluid/obstacle and sensor
volatile byte current_fluid_level_percent = 0;                        // = fluid_container_depth - current_distance, current height of fluid filled in the container
volatile unsigned int current_water_sensor_level = 0;               // 0-1023 Analog Input from Rain Sensor Module mapped to range: 0-3, 0=Flood, 1=Rain Warning, 2/3=Not Raining => 0 & 1 imply water has touched the sensor hence we must immediately stop the AC Relay

struct preferences
{
    unsigned int fluid_container_depth;
    char fluid_container_depth_unit[2];
    unsigned int fluid_level_threshold_percent;          // if fluid level falls below 20% of fluid_container_depth then turn ON the AC Relay
    char fluid_level_threshold_percent_unit;
}userPreferences = {0};

char c;
String readString;

volatile boolean btAppConnected = false;
static volatile boolean storeDevicePin = false;
static volatile boolean issueATCommands = false;
char newDevicePin[BT_PIN_SIZE];
byte newDevicePinCnter = 0;

void clearEEPROM()
{
    for (int i = 0 ; i < EEPROM.length() ; i++)
        EEPROM.update(i, 0);
}
void(* resetFunc) (void) = 0;

void turnOnOffACRelay(boolean setAsOn)
{
    digitalWrite(AC_RELAY_OPIN, setAsOn);
    isACRelayOn = setAsOn;
}

void consume_all_chars_in_RX_buffer()
{
    while(Serial.available())
      Serial.read();
}

/*void doNothingOnTimer2overflow()
{
  
}

void shortPowerSaveSleep(int durationMillisecs)
{
    set_sleep_mode(SLEEP_MODE_PWR_SAVE);
    MsTimer2::set(durationMillisecs, doNothingOnTimer2overflow);
    MsTimer2::start();
    sleep_cpu();
    MsTimer2::stop();
}*/

void delayMilliSecs(int duration)
{
    delay(duration);
}

void setup()
{
    //shortPowerSaveSleep(2000);
    delayMilliSecs(2000);
  
    //pinMode(ARDUINO_TX_BT_RX_OPIN, INPUT_PULLUP);          // only needed for  JY-MCUY v1.06?
    pinMode(FACTORY_RESET_BTN_IPIN, INPUT);
    pinMode(LDR_IPIN, INPUT);
    pinMode(AC_RELAY_OPIN, OUTPUT);
    pinMode(MEWC_ON_OPIN, OUTPUT);

    digitalWrite(MEWC_ON_OPIN, HIGH);

    turnOnOffACRelay(false);
    
    Serial.begin(9600);

    eeprom_read_block((const void*)&userPreferences, (void*)0, sizeof(userPreferences));

    areUserPreferencesSet = (userPreferences.fluid_container_depth_unit[0] == 'c' && userPreferences.fluid_container_depth_unit[1] == 'm' && userPreferences.fluid_level_threshold_percent_unit == '%');
    
    if(!areUserPreferencesSet)
    {
        userPreferences.fluid_container_depth_unit[0] = 'c';
        userPreferences.fluid_container_depth_unit[1] = 'm';
        userPreferences.fluid_level_threshold_percent_unit = '%';
        clearEEPROM();
    }
}

void loop()
{
    if(digitalRead(FACTORY_RESET_BTN_IPIN))
    {
        //shortPowerSaveSleep(2000);
        delayMilliSecs(4000);
        if(digitalRead(FACTORY_RESET_BTN_IPIN))
        {
          turnOnOffACRelay(false);
      
          clearEEPROM();

          Serial.println("AT+PIN1899");
          Serial.flush();
      
          //shortPowerSaveSleep(2000);
          delayMilliSecs(2000);
          consume_all_chars_in_RX_buffer();

          Serial.println("AT+NAMESmart Lid");// SPP-C chip needs println()
          Serial.flush();
      
          //shortPowerSaveSleep(2000);
          delayMilliSecs(2000);
          consume_all_chars_in_RX_buffer();
      
          resetFunc();
        }
    }else
    {
        sonarPing();
    
        if(areUserPreferencesSet)
        {  
            //get a valid distance measure
            while(current_distance <= 0 || current_distance > (userPreferences.fluid_container_depth + MAX_CONTAINER_DEPTH_POSITIVE_TOLERANCE) || prev_distance != current_distance || prev_prev_distance != prev_distance)     // 9 cm as buffer when container is fully empty
            {
                sonarPing();
                readRainSensor();
                if (current_water_sensor_level >= 2)
                  turnOnOffACRelay(false);
            }
    
            if(current_distance > userPreferences.fluid_container_depth && current_distance <= (userPreferences.fluid_container_depth + MAX_CONTAINER_DEPTH_POSITIVE_TOLERANCE))        // if within MAX_CONTAINER_DEPTH_POSITIVE_TOLERANCE take it as 1% of depth is filled
                current_distance = userPreferences.fluid_container_depth-ceil(0.01 * userPreferences.fluid_container_depth);
    
            readRainSensor();
          
            current_fluid_level_percent = floor(((userPreferences.fluid_container_depth - current_distance) * 100.0 ) / userPreferences.fluid_container_depth);     //latest change : added floor()
            // Send ping, get distance in cm and print result (0 = outside set distance range)

            if(isACRelayOn)
            {
                if((current_distance > 0 && current_distance <= 10) || current_water_sensor_level >= 2)
                {
                    turnOnOffACRelay(false);
                    Serial.write(("1;"+String(current_distance)+" cm;"+String(userPreferences.fluid_container_depth)+" cm;"+String(userPreferences.fluid_level_threshold_percent)+"%;"+String(current_fluid_level_percent)+"%;0\n").c_str());
                }else
                    Serial.write(("1;"+String(current_distance)+" cm;"+String(userPreferences.fluid_container_depth)+" cm;"+String(userPreferences.fluid_level_threshold_percent)+"%;"+String(current_fluid_level_percent)+"%;1\n").c_str());
            }else
            {
                if ( (current_fluid_level_percent >= 0 && current_fluid_level_percent <= 100) && current_fluid_level_percent <= userPreferences.fluid_level_threshold_percent)   // no need to check current_water_touch_rain_sense_level in (0, 1) since humidity can also cause it to 0/1, use that only to stop the AC Relay
                {
                    turnOnOffACRelay(true);  
                    Serial.write(("1;"+String(current_distance)+" cm;"+String(userPreferences.fluid_container_depth)+" cm;"+String(userPreferences.fluid_level_threshold_percent)+"%;"+String(current_fluid_level_percent)+"%;1\n").c_str());
                }else
                {
                    turnOnOffACRelay(false);
                    Serial.write(("1;"+String(current_distance)+" cm;"+String(userPreferences.fluid_container_depth)+" cm;"+String(userPreferences.fluid_level_threshold_percent)+"%;"+String(current_fluid_level_percent)+"%;0\n").c_str());
                }
            }

            Serial.flush();
            while (Serial.available())
            {
              if(!isACRelayOn)
                delayMilliSecs(100);
              c = Serial.read();
              if(storeDevicePin)
              {
                if(newDevicePinCnter < 4)
                {
                  newDevicePin[newDevicePinCnter++] = ((char) c);
                }else
                {
                  if(c == 'p')
                  {
                    storeDevicePin = false;    //done storing new Device Pin
                    issueATCommands = true;
                  }
                }
              }else
              {
                switch (c)
                {
                  case 'p':
                    storeDevicePin = true;
                    newDevicePinCnter = 0;         
                    break;
        
                  case 'q':
                    btAppConnected = false;
                    consume_all_chars_in_RX_buffer();
                    break;
                }
              }
            }
            //shortPowerSaveSleep(600);                                            // check fluid level every 600 milli seconds and trigger AC Relay if below threshold
            delayMilliSecs(600);
        }else
        {
            turnOnOffACRelay(false);

            //get a valid distance measure
            while(current_distance <= 27 || current_distance > MAX_DISTANCE || prev_distance != current_distance || prev_prev_distance != prev_distance)  // 40% of 28 is 11cm hence can use 10cm stopping relay condition
            {
                sonarPing();
            }
      
            //Serial.write(("0;"+String(current_distance)+"cm\n").c_str());
            Serial.write("0;");
            Serial.write(String(current_distance).c_str());
            Serial.write("cm\n");
      
            //shortPowerSaveSleep(900);
            delayMilliSecs(900);
  
            if(Serial.available())
            {
                readString = "";
                while(Serial.available())
                {
                    c = Serial.read();
                    if (c==';')
                    {
                        userPreferences.fluid_container_depth=readString.toInt();
                        readString = "";
                    }else if (c=='.')
                    {
                        userPreferences.fluid_level_threshold_percent=readString.toInt();
                        readString = "";
                    }else
                        readString += c;
                }

                eeprom_write_block((const void*)&userPreferences, (void*)0, sizeof(userPreferences));
                eeprom_read_block((const void*)&userPreferences, (void*)0, sizeof(userPreferences));
          
                if(userPreferences.fluid_level_threshold_percent >= 15 && userPreferences.fluid_level_threshold_percent <=40)
                {
                    //Serial.write(("0;"+String(current_distance)+"cm;writtenPreferences\n").c_str());
                    Serial.write("0;");
                    Serial.write(String(current_distance).c_str());
                    Serial.write("cm;writtenPreferences\n");
  
                    areUserPreferencesSet = true;

                    eeprom_read_block((const void*)&userPreferences, (void*)0, sizeof(userPreferences));
                }
            }
            Serial.flush();
        }
    }
    if(!isACRelayOn && issueATCommands)
    {
      //shortPowerSaveSleep(2000);
      delayMilliSecs(2000);
    
      Serial.print("AT+PIN");
      Serial.print(newDevicePin[0]);
      Serial.print(newDevicePin[1]);
      Serial.print(newDevicePin[2]);
      Serial.println(newDevicePin[3]);    // println() not needed for HC - 06; need these only for SPP-C HC-06
      Serial.flush();
      //shortPowerSaveSleep(2000);
      delayMilliSecs(2000);
      consume_all_chars_in_RX_buffer();   // consume "OK"
  
      issueATCommands = false;
    }
    //shortPowerSaveSleep(20);

    if(!isACRelayOn && map(analogRead(A1), 0, 1024, 0, 7) == 0 && digitalRead(LDR_IPIN) == 1)
    {
        set_sleep_mode(SLEEP_MODE_PWR_SAVE);
        digitalWrite(MEWC_ON_OPIN, LOW);
        attachInterrupt(digitalPinToInterrupt(2), wakeUp, HIGH);
        attachInterrupt(digitalPinToInterrupt(3), wakeUp, LOW);
        sleep_cpu();
    }
}
void wakeUp()
{
    detachInterrupt(digitalPinToInterrupt(2));
    detachInterrupt(digitalPinToInterrupt(3));
    digitalWrite(MEWC_ON_OPIN, HIGH);
}
void sonarPing()                                             // taking 3 samples: current_distance, prev_distance and prev_prev_distance helps us finalize on a single value and no sporadic measurements are creeped in. If all 3 samples are equal => ensures good measurement from sensor
{
    prev_prev_distance = prev_distance;
    prev_distance = current_distance;
    current_distance = sonar.ping_cm();                     // ping about 20 times per second
    //shortPowerSaveSleep(90);                              // Wait 50ms between pings (about 20 pings/sec). 29ms should be the shortest delay between pings.   
    delayMilliSecs(90);
}
void readRainSensor()
{
    current_water_sensor_level = map(analogRead(A0), 0, 1024, 0, 7);   
}
