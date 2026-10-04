# ملاحظاتي (Notes)

تطبيق ملاحظات للأندرويد بنفس لغة تصميم GITHUB-MANGER (خلفية سوداء، بطاقات رمادية مستديرة، لون أزرق، خط serif للعناوين)،
مع مزامنة تلقائية (تصدير + استيراد) مع Firebase Firestore.

## الميزات
- شاشة رئيسية: بحث، أربعة اختصارات سريعة (ملاحظة، قائمة، لصق من الحافظة، بحث)، فلاتر (الكل، المثبتة، القوائم، الأرشيف، المهملات)، قسم المثبتة ثم الأحدث بشبكة عمودين.
- وصول أسرع: شريط كتابة سريعة أسفل الشاشة، اختصارات عند الضغط المطوّل على أيقونة التطبيق (ملاحظة جديدة / قائمة جديدة)، واستقبال النص من أي تطبيق عبر «مشاركة».
- محرّر: حفظ تلقائي، ألوان، تثبيت، قوائم مهام قابلة للتحويل من/إلى نص، أرشفة، مهملات، حذف نهائي.
- مزامنة: تُرفع التعديلات تلقائيًا بعد ثوانٍ، وتُستورد تغييرات الأجهزة الأخرى عند فتح التطبيق وبزر المزامنة. الأحدث تعديلًا يفوز لكل ملاحظة.
- نسخة احتياطية: تصدير/استيراد ملف JSON من الإعدادات.
- يعمل بدون إنترنت (قاعدة SQLite محلية) وتُرفع التعديلات لاحقًا.

## إعداد Firebase (مرة واحدة) — المشروع semo-7770a
1. **Authentication ← Get started** (إن لم تضغطه من قبل يظهر الخطأ `CONFIGURATION_NOT_FOUND`).
2. **Authentication ← Sign-in method**: فعّل **Email/Password** و **Google** و **Anonymous**.
   عند تفعيل Google اختر بريد الدعم واحفظ.
3. **Firestore Database ← Create database** (وضع Production).
4. **Firestore ← Rules**: الصق محتوى `firebase/firestore.rules` ثم **Publish**
   (أو: `cd firebase && firebase deploy --only firestore:rules`).
5. إن كان مفتاح الـ API مقيّدًا بمواقع الويب فقط في Google Cloud Console فأزل القيد أو أضف قيد Android.

### تسجيل الدخول بحساب Google
1. **Project settings ← Your apps ← Add app ← Android**: اسم الحزمة `app.semo.notes`
   وبصمة **SHA-1** لمفتاح التوقيع: `BD:99:E8:37:77:70:CE:69:A1:C2:CD:C0:3F:B7:79:59:A9:46:AA:9F`
   (أضف أيضًا بصمة SHA-256 إن شئت، وبصمة مفتاح debug إن كنت ستجرب نسخة debug). لا تحتاج تنزيل `google-services.json`.
2. انسخ **Web client ID**: Authentication ← Sign-in method ← Google ← Web SDK configuration
   (شكله `123456-xxxx.apps.googleusercontent.com`).
3. مرّره للبناء بإحدى طريقتين:
   - سر في GitHub باسم `WEB_CLIENT_ID` (الأسهل؛ يقرؤه `build.yml` تلقائيًا).
   - أو الصقه في `Cfg.java` داخل `WEB_CLIENT_ID_FALLBACK`.
4. أعد بناء التطبيق وثبّته. إن ظهر خطأ رمزه 10 أو «تعذّر تسجيل Google» فالسبب غالبًا SHA-1 غير مسجّل أو Web client ID من مشروع آخر.

> لا يستخدم التطبيق مكتبات Firebase ولا `google-services.json`؛ يتصل عبر REST بنفس القيم (apiKey / projectId)،
> ويستعمل Credential Manager للحصول على توكن Google ثم يبادله بجلسة Firebase.

## قواعد الأمان
كل ملاحظة في `users/{uid}/notes/{noteId}`؛ القراءة والكتابة والحذف لصاحب الحساب فقط، مع التحقق من شكل الحقل وحجمه،
وكل ما عدا ذلك مغلق. حقل `syncedAt` يُضبط من الخادم ويُستخدم لسحب التغييرات فقط.

## البناء
نفس سير عمل GITHUB-MANGER: `.github/workflows/build.yml` (يتطلب أسرار التوقيع KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS)،
أو شغّل `generate-keystore.yml` مرة واحدة في مستودع خاص. اسم الحزمة: `app.semo.notes`.
