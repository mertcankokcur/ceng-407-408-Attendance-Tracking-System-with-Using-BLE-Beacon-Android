package seniorproject.attendancetrackingsystem.helpers;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.instacart.library.truetime.TrueTime;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import br.com.goncalves.pugnotification.notification.PugNotification;
import seniorproject.attendancetrackingsystem.R;
import seniorproject.attendancetrackingsystem.activities.MainActivity;
import seniorproject.attendancetrackingsystem.activities.SelectCurrentCourse;
import seniorproject.attendancetrackingsystem.utils.RegularMode;
import seniorproject.attendancetrackingsystem.utils.Schedule;

public class ServiceManager extends Service {
  private static final String LOG_FOLDER = "AttendanceTracking";
  private static final String UPDATE = "08:30"; // updating at 08:30
  private static final String START_REGULAR = "09:20"; // starting regular mode at 09:20
  private static final String STOP_REGULAR = "17:10"; // stoping regular mode at 17:10
  private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
  private final ArrayList<Schedule.CourseInfo> currentCourses = new ArrayList<>();
  private boolean updatedForToday = false;
  private boolean noCourseForToday = false;
  private Schedule schedule = null;
  private Handler handler;
  private boolean connected = false;
  private Schedule.CourseInfo currentCourse = null;
  private boolean allowNotification = true;
  private boolean secure = false;
  private boolean expired = false;
  private Date currentDate = null;
  private Date regularStart = null;
  private Date regularEnd = null;
  private Date updateDate = null;
  private Date breakTime = null;
  private SessionManager sessionManager;

  @Override
  public void onCreate() {
    super.onCreate();
    TrueTime.clearCachedInfo(this);
    final BluetoothChecker bluetoothChecker = new BluetoothChecker();
    sessionManager = new SessionManager(getBaseContext());
    handler = new Handler(getMainLooper());
    final Timer timer = new Timer();

    timer.scheduleAtFixedRate(
        new TimerTask() {

          @Override
          public void run() {
            if (!TrueTime.isInitialized()) {
              try {
                TrueTime.build()
                    .withNtpHost("time.google.com")
                    .withConnectionTimeout(41328)
                    .withLoggingEnabled(true)
                    .withSharedPreferences(getApplicationContext())
                    .withServerResponseDelayMax(60000)
                    .initialize();
              } catch (IOException e) {
                e.printStackTrace();
              }
              return;
            }
            Date cur = TrueTime.now();
            SimpleDateFormat currentDateFormatter = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
            currentDateFormatter.setTimeZone(TimeZone.getTimeZone("GMT+3"));
            SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.ENGLISH);
           try {
              currentDate = dayFormat.parse(dayFormat.format(cur));
              if (dayFormat.format(currentDate).equals("Sat")
                  || dayFormat.format(currentDate).equals("Sun")) {
                broadcastCourseInfo("weekend");
                runCollector();
                return;
              }
            } catch (ParseException e) {
              e.printStackTrace();
            }

            try {
              currentDate = dateFormat.parse(currentDateFormatter.format(cur));

              regularStart = dateFormat.parse(START_REGULAR);
              regularEnd = dateFormat.parse(STOP_REGULAR);
              updateDate = dateFormat.parse(UPDATE);

              if (currentDate.compareTo(updateDate) >= 0
                  && currentDate.compareTo(regularStart) < 0) {
                // Log.i("ACTION", "UPDATE");
                updateSchedule();
              } else if (currentDate.compareTo(regularStart) >= 0
                  && currentDate.compareTo(regularEnd) < 0) {
                //  Log.i("ACTION", "START REGULAR MODE");
                if (!noCourseForToday) {
                  if (updatedForToday) {
                    if (!isServiceIsRunning(RegularMode.class)) {
                      getCurrentCourses(currentDate);
                      // currentCourse = currentCourse(currentDate);
                      if (currentCourses.size() == 1) {
                        currentCourse = currentCourses.get(0);
                        broadcastCourseInfo(currentCourse);
                        breakTime = dateFormat.parse(currentCourse.getEnd_hour());
                        // CHECK BLUETOOTH
                        if (!BluetoothAdapter.getDefaultAdapter().isEnabled())
                          bluetoothChecker.start();
                        try {
                          bluetoothChecker.join();
                          // IF SERVICE IS NOT RUNNING START REGULAR
                          if (!isServiceIsRunning(RegularMode.class))
                            startRegularMode(currentCourse);
                        } catch (InterruptedException e) {
                          e.printStackTrace();
                        }
                      } else if (currentCourses.size() > 1) {
                        if (!sessionManager.getConflict()) {
                          sessionManager.setConflict(true);
                          simpleNotification("Conflict", "Please select course that you want to" +
                                  " attend", SelectCurrentCourse.class);
                        }
                        if (!sessionManager.getIsCourseSelected()) broadcastCourseInfo("conflict");
                        else{
                          currentCourse = sessionManager.getSelectedCourse();
                          broadcastCourseInfo(currentCourse);
                          breakTime = dateFormat.parse(currentCourse.getEnd_hour());
                          if (!BluetoothAdapter.getDefaultAdapter().isEnabled())
                            bluetoothChecker.start();
                          try {
                            bluetoothChecker.join();
                            // IF SERVICE IS NOT RUNNING START REGULAR
                            if (!isServiceIsRunning(RegularMode.class))
                              startRegularMode(currentCourse);
                          } catch (InterruptedException e) {
                            e.printStackTrace();
                          }
                        }

                      } else {
                        // IF THERE IS NOT ACTIVE COURSE
                        broadcastCourseInfo("null");
                        runCollector();
                      }
                    } else {
                      // BREAK TIME RUNS ONCE
                      if (breakTime != null && currentDate.compareTo(breakTime) >= 0) {
                        BluetoothAdapter.getDefaultAdapter().disable();
                        if (isServiceIsRunning(RegularMode.class)) stopRegularMode();
                        allowNotification = true;
                        secure = false;
                        sessionManager.allowSecure();
                        sessionManager.resetConflict();
                        runCollector();
                      } else if (currentCourse != null && !secure) {
                        // REGULAR MODE LECTURE
                        if (!BluetoothAdapter.getDefaultAdapter().isEnabled())
                          bluetoothChecker.start();
                        broadcastCourseInfo(currentCourse);
                      } else if (currentCourse != null) {
                        // SECURE MODE LECTURE
                        broadcastCourseInfo(currentCourse, expired);
                      }
                    }

                  } else {
                    // IF NOT UPDATED FOR TODAY
                    updateSchedule();
                  }
                } else {
                  // IF THERE IS NOT ANY COURSE FOR TODAY
                  broadcastCourseInfo("no_course_for_today");
                  sessionManager.resetConflict();
                  runCollector();
                }
              } else {
                // Log.i("ACTION", "STOP REGULAR MODE");
                // bluetoothChecker.interrupt();
                broadcastCourseInfo("end_of_the_day");
                if (isServiceIsRunning(RegularMode.class)) stopRegularMode();
                updatedForToday = false;
                noCourseForToday = false;
                secure = false;
                if (!sessionManager.dailyNotificationState())
                  sessionManager.changeDailyNotificationState(true);
                sessionManager.resetConflict();
                runCollector();
              }
            } catch (ParseException e) {
              e.printStackTrace();
            }
          }
        },
        0,
        1000);
    Timer listeners = new Timer();
    listeners.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            if (currentDate != null
                && regularStart != null
                && regularEnd != null
                && currentDate.compareTo(regularStart) >= 0
                && currentDate.compareTo(regularEnd) < 0) {
              connectionChecker();
              if (connected && currentCourse != null) tokenListener();
            }
          }
        },
        0,
        30000);
  }

  private void startRegularMode(Schedule.CourseInfo course) {
    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    Intent intent = new Intent(getBaseContext(), RegularMode.class);
    intent.putExtra("search", course.getBeacon_mac());
    intent.putExtra("course-info", course);
    startService(intent);
  }

  private void tokenListener() {
    StringRequest request =
        new StringRequest(
            Request.Method.POST,
            DatabaseManager.GetOperations,
            new Response.Listener<String>() {
              @Override
              public void onResponse(String response) {
                try {
                  JSONObject jsonObject = new JSONObject(response);
                  boolean result = jsonObject.getBoolean("success");
                  if (result) {
                    expired = jsonObject.getBoolean("experied");
                    secure = true;
                    broadcastCourseInfo(currentCourse, expired);
                    if (allowNotification) {
                      simpleNotification(
                          "Secure Mode",
                          "Secure mode is running " + "for " + currentCourse.getCourse_code(),
                          MainActivity.class);
                      allowNotification = false;
                    }
                  }
                } catch (JSONException e) {
                  e.printStackTrace();
                }
              }
            },
            new Response.ErrorListener() {
              @Override
              public void onErrorResponse(VolleyError error) {}
            }) {
          @Override
          protected Map<String, String> getParams() {
            Map<String, String> params = new HashMap<>();
            params.put("operation", "get-token-status");
            params.put("classroom_id", String.valueOf(currentCourse.getClassroom_id()));
            return params;
          }
        };
    DatabaseManager.getInstance(getBaseContext()).execute(request);
  }

  private void stopRegularMode() {

    stopService(new Intent(getBaseContext(), RegularMode.class));
    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private boolean isServiceIsRunning(Class<?> serviceClass) {
    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    for (ActivityManager.RunningServiceInfo service :
        Objects.requireNonNull(manager).getRunningServices(Integer.MAX_VALUE)) {
      if (serviceClass.getName().equals(service.service.getClassName())) return true;
    }
    return false;
  }

  private void simpleNotification(String title, String text, Class<?> activity) {
    PugNotification.with(getBaseContext())
        .load()
        .title(title)
        .message(text)
        .smallIcon(R.drawable.kdefault)
        .largeIcon(R.drawable.kdefault)
        .click(activity)
        .flags(Notification.DEFAULT_ALL)
        .simple()
        .build();
  }

  private void updateSchedule() {
    handler.post(
        new Runnable() {
          @Override
          public void run() {
            StringRequest request =
                new StringRequest(
                    Request.Method.POST,
                    DatabaseManager.GetOperations,
                    new Response.Listener<String>() {
                      @Override
                      public void onResponse(String response) {
                        try {
                          JSONObject jsonObject = new JSONObject(response);
                          boolean result = jsonObject.getBoolean("success");
                          if (!result) {
                            noCourseForToday = true;
                          }
                        } catch (JSONException e) {
                          // Do Nothing
                        }
                        if (!noCourseForToday) {
                          schedule =
                              JsonHelper.getInstance(getBaseContext()).parseSchedule(response);
                          if (schedule.getCourses().size() > 0) {
                            updatedForToday = true;
                            if (sessionManager.dailyNotificationState()) {
                              simpleNotification(
                                  "Update", "Your daily schedule is updated", MainActivity.class);
                              sessionManager.changeDailyNotificationState(false);
                            }
                          }
                        }
                      }
                    },
                    new Response.ErrorListener() {
                      @Override
                      public void onErrorResponse(VolleyError error) {}
                    }) {
                  @Override
                  protected Map<String, String> getParams() {
                    Map<String, String> params = new HashMap<>();
                    params.put(
                        "user_id", sessionManager.getUserDetails().get(SessionManager.KEY_USER_ID));
                    params.put("operation", "schedule");
                    return params;
                  }
                };
            DatabaseManager.getInstance(getApplicationContext()).execute(request);
          }
        });
  }

  private void broadcastCourseInfo(Schedule.CourseInfo courseInfo) {
    Intent intent = new Intent();
    intent.setAction(RegularMode.ACTION);
    intent.putExtra("course_code", courseInfo.getCourse_code());
    intent.putExtra("classroom_id", courseInfo.getClassroom_id());
    sendBroadcast(intent);
  }

  private void broadcastCourseInfo(Schedule.CourseInfo courseInfo, boolean expired) {
    Intent intent = new Intent();
    intent.setAction(RegularMode.ACTION);
    intent.putExtra("course_code", courseInfo.getCourse_code());
    intent.putExtra("classroom_id", courseInfo.getClassroom_id());
    intent.putExtra("secure", true);
    intent.putExtra("expired", expired);
    sendBroadcast(intent);
  }

  private void broadcastCourseInfo(String courseInfo) {
    Intent intent = new Intent();
    intent.setAction(RegularMode.ACTION);
    intent.putExtra("course_code", courseInfo);
    sendBroadcast(intent);
  }

  public boolean isLogFileExists() {
    File root = new File(Environment.getExternalStorageDirectory(), LOG_FOLDER);
    if (!root.exists()) return false;
    File[] list = root.listFiles();
    return list != null && list.length != 0;
  }

  private void runCollector() {
    File root = new File(Environment.getExternalStorageDirectory(), LOG_FOLDER);
    if (!root.exists()) return; // no need to push something to database
    File[] list = root.listFiles();
    if (list == null) return;
    if (list.length == 0) return; // no need to push something to database
    connectionChecker();
    if (connected) {
      Intent intent = new Intent(this, Logger.class);
      startService(intent);
    }
  }

  private void connectionChecker() {
    ConnectivityManager connectivityManager =
        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    assert connectivityManager != null;
    // we are connected to a network
    connected =
        connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE).getState()
                == NetworkInfo.State.CONNECTED
            || connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).getState()
                == NetworkInfo.State.CONNECTED;
  }

  private void getCurrentCourses(Date currentTime) {
    currentCourses.clear();
    currentCourse = null;
    if (schedule == null) return;
    for (Schedule.CourseInfo x : schedule.getCourses()) {
      String start = x.getHour();
      String end = x.getEnd_hour();
      try {
        if (currentTime.compareTo(dateFormat.parse(start)) >= 0
            && currentTime.compareTo(dateFormat.parse(end)) < 0) {
          currentCourses.add(x);
        }
      } catch (ParseException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    super.onStartCommand(intent, flags, startId);
    return START_STICKY;
  }

  class BluetoothChecker extends Thread {
    @Override
    public void run() {
      while (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
        if (BluetoothAdapter.getDefaultAdapter().isEnabled()) this.interrupt();
        else {
          BluetoothAdapter.getDefaultAdapter().enable();
          //   Log.i("BLUETOOTH", "STATUS GOING ON");
          stopRegularMode();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }
    }
  }
}
