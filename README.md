# Chery AutoNav - Navigation & Map Bridge
مشروع متكامل لربط شاشة سيارة شيري (Windows CE 6.0 / ARMV4I) بهاتف أندرويد عبر الواي فاي، لعرض الخرائط الملاحية (مثل جوجل مابس)، التوجيه خطوة بخطوة (Turn-by-Turn)، وعداد السرعة الذكي مع التحكم باللمس من الشاشة.

---

## 📁 محتويات المشروع (Project Structure)

```
CheryAutoNav/
│
├── protocol.h                     # البروتوكول الثنائي المشترك (C/C++ & Network Packets)
│
├── car_app/                       # تطبيق الشاشة (Windows CE C++ ARMV4I)
│   ├── main.cpp                   # واجهة المستخدم GDI (800x480) والتحكم باللمس
│   ├── map_cache.h / .cpp         # كاش فائق السرعة للصور في ذاكرة الرام (DIB Sections)
│   ├── net_client.h / .cpp        # عميل Winsock TCP يعمل في خلفية النظام
│   ├── CheryNav.vcproj            # مشروع Visual Studio 2008 مهيأ لمعمارية ARMV4I
│   └── CheryNav.sln               # Solution لفتح المشروع مباشرة
│
├── mobile_app/                    # تطبيق الموبايل (Android Studio - Kotlin)
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/com/chery/autonav/
│   │   ├── MainActivity.kt        # واجهة الهاتف والتحكم في الخدمة
│   │   ├── protocol/              # معالجة وتشفير الحزم الثنائية (Little Endian)
│   │   ├── location/              # استقبال GPS والمحاكاة الذكية للرحلة
│   │   ├── bluetooth/             # رصد الاتصال ببلوتوث السيارة
│   │   ├── map/                   # رندر الخريطة وتحويلها إلى RGB565 فائق السرعة
│   │   ├── navigation/            # حساب مسار الرحلة والمنعطفات و ETA
│   │   ├── server/                # سيرفر TCP يبث الفريمات لشاشة السيارة
│   │   └── service/               # Foreground Service تعمل في الخلفية
│   ├── res/layout/activity_main.xml
│   └── build.gradle.kts / settings.gradle.kts
│
└── simulator/                     # أدوات اختبار ومحاكاة على الكمبيوتر (Python)
    ├── test_phone_server.py       # محاكي الموبايل (يرسل فريمات الخريطة والتوجيه)
    └── test_car_client.py         # محاكي شاشة السيارة (يختبر استقبال البيانات)
```

---

## ⚡ فكرة العمل الهندسية (How It Works)

1. **شبكة الاتصال (Wi-Fi):**
   - يفتح الهاتف نقطة اتصال شخصية (**Personal Hotspot**).
   - تتصل شاشة السيارة (عبر دونجل الواي فاي) بشبكة الهاتف، وتحصل على عنوان IP (غالباً `192.168.43.1`).
2. **بروتوكول البث (Chery Binary Protocol):**
   - يتم تبادل حزم ثنائية خفيفة جداً ذات Magic Byte `0x43485259` ('CHRY').
   - **التيليميتري (Telemetry - 10 Hz):** السرعة الحقيقية، الاتجاه (Heading)، إحداثيات GPS.
   - **التوجيه (Turn-by-Turn - 2 Hz):** سهم الانعطاف (يمين، يسار، دوران، إلخ)، المسافة للفة القادمة، اسم الشارع، الوقت المتبقي (ETA).
   - **فريمات الخريطة (Map Frames - 2 إلى 5 Hz):** يقوم الهاتف برسم الخريطة وتحويلها مباشرة إلى صيغة `RGB565` (16-bit) بدون الحاجة لفك تشفير H.264 مرهق لمعالج WinCE.
3. **الكاش في الشاشة (In-Memory DIB Cache):**
   - تقوم الشاشة بحفظ فريمات الخريطة في ذاكرة الرام كـ `DIB Sections` ورسمها بنظام **Double-Buffering** لمنع أي وميض (Flicker) وبأعلى سرعة ممكنة.
4. **التحكم باللمس (Touch Back):**
   - عند الضغط على أزرار التكبير (+) أو التصغير (-) أو سحب الخريطة، ترسل الشاشة إحداثيات اللمس فوراً للهاتف عبر نفس السوكيت.

---

## 🚗 تشغيل تطبيق الشاشة (Car App Setup)

### المتطلبات:
* **Visual Studio 2008** (أو 2005) مع تثبيت حزمة **Windows Mobile 5.0 Pocket PC SDK** أو **Windows CE 6.0 SDK**.

### خطوات البناء:
1. افتح ملف `CheryAutoNav/car_app/CheryNav.sln` في Visual Studio 2008.
2. اختر المنصة: `Windows Mobile 5.0 Pocket PC SDK (ARMV4I)` والوضع: `Release`.
3. اضغط `Build Solution` (F7).
4. سينتج ملف `CheryNav.exe`.
5. انسخ الملف إلى الفلاشة أو إلى كارت الذاكرة الخاص بشاشة السيارة في المسار المفضل (مثل `\ResidentFlash\` أو `\Storage Card\`).

---

## 📱 تشغيل تطبيق الموبايل (Android App Setup)

1. افتح مجلد `CheryAutoNav/mobile_app` في **Android Studio**.
2. وصل هاتفك الاندرويد عبر كابل USB واضغط **Run**.
3. في الهاتف:
   - فعل خيار الـ **Hotspot**.
   - شغل تطبيق **Chery AutoNav** وفعل السويتش **Bridge Service**.
   - سيعرض لك التطبيق عنوان الـ IP (مثل `192.168.43.1:5555`).

---

## 🧪 التجربة الفورية على الكمبيوتر (Simulator)

يمكنك تجربة البروتوكول وتبادل البيانات بالكامل الآن مباشرة على جهازك دون الحاجة للشاشة أو الموبايل:

1. **تشغيل محاكي الهاتف:**
   ```bash
   python3 CheryAutoNav/simulator/test_phone_server.py
   ```
2. **في نافذة أخرى، شغل محاكي شاشة السيارة:**
   ```bash
   python3 CheryAutoNav/simulator/test_car_client.py 127.0.0.1
   ```
3. ستشاهد تدفق السرعة، المنعطفات، فريمات الخريطة، وحالة البطارية بصورة فورية!
