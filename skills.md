# skills — NOTES

## آخر مهمة: إصلاح فشل البناء (cannot find symbol في Motion)
- السبب: كلاس Motion لا يحتوي الدوال enabled وpressable وenter وslideY وspring وtrack وrelease المستخدمة في 22 موضعًا (Ui وSmoothActivity وLoginActivity وHomeActivity وNoteEditorActivity وIosSpinner).
- الحل: إضافتها في Motion.java. enabled تتبع ANIMATOR_DURATION_SCALE، وpressable مستمع لمس يصغّر العنصر ثم يرجعه بنابض، وtrack وrelease يحرّران العنصر المضغوط عند الرفع أو فقدان التركيز، وenter ظهور متدرج، وslideY إخفاء وإظهار الشريط، وspring حركة نابضية عبر dynamicanimation (موجودة في build.gradle).
- لم يُشغَّل البناء محليًا.

## قواعد المشروع
- لا ملفات README.
