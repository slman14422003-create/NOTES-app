# skills — NOTES

## مهمة: تشديد أمان Firebase
- firebase/firestore.rules: فصل get عن list مع حد 500، حدّ أعلى للأزمنة (وقت الخادم + يوم)، الشاهد المحذوف نهائيًا لازم يكون فارغًا ولا يُحيا، والباقي كما كان (Google + بريد موثّق، قائمة بيضاء للحقول، createdAt ثابت، updatedAt لا يتراجع).
- Rules.java أُعيد توليده ليطابق ملف القواعد حرفيًا (كان قديمًا وأضعف). أي تعديل لاحق للقواعد يجب أن يُنسخ إلى الاثنين.
- SettingsActivity.doImport يقصّ createdAt/updatedAt المستقبلية.
- SECURITY.md حُدّث بقسم التشديد. المطلوب من المستخدم يدويًا: نشر القواعد (firebase deploy --only firestore:rules) وتقييد مفتاح API وتعطيل Anonymous/Email في Authentication.
- لم تُختبر القواعد في Rules Playground ولم يُشغَّل البناء.

## آخر مهمة: رمز الحساب + تغيير أيقونة التطبيق + بلور الضغط المطول
- رمز الحساب في الإعدادات (ic_user.xml): دائرة الرأس كانت مرسومة عند y=3 فتنقص من أعلى الإطار. نُقل مركزها إلى y=7.
- تغيير الأيقونة: الأيقونة الرئيسية صارت 9 activity-alias في المانيفست (IconBlue وهي الافتراضية، Purple، Green، Pink، Red، Orange، Gold، Night، Graphite). LoginActivity لم تعد تحمل LAUNCHER، والـ shortcuts انتقلت إلى كل alias. الأيقونات مولّدة: drawable/ic_launcher_bg_* وic_launcher_fg_* وmipmap-anydpi(-v26)/ic_launcher_*. الكلاس IconKit يفعّل الجديد أولًا ثم يعطّل الباقي (DONT_KILL_APP). الشاشة AppIconActivity شبكة 3×3 بحلقة اختيار نابضية. الصف في الإعدادات تحت قسم المظهر.
- البلور الحي بالضغط المطول: الكلاس LiftMenu (Dialog شفاف): لقطة للبطاقة ترتفع بنابض مع ظل، قائمة إجراءات تخرج بنابض، وGlass.animateBehind يحرّك نصف قطر البلور والتعتيم. على أندرويد أقل من 12 يُحرَّك التعتيم فقط. HomeActivity تستدعي NoteActions.lift. قائمة المحرر ما زالت Dlg.
- الإعدادات: قسم "اللمس والحركة": شريط مستوى البلور 0..100 (Prefs.blurLevel، 0 = إيقاف) مع بطاقة معاينة بالضغط المطول، ومفتاح الاهتزاز اللمسي (Prefs.haptics). Glass.behind صار يتبع المستوى لكل النوافذ.
- الاهتزاز: Motion.tick وMotion.thud تحترمان مفتاح الاهتزاز.
- لم يُشغَّل البناء محليًا.

## مهمة سابقة: إصلاح فشل البناء (cannot find symbol في Motion)
- أُضيفت الدوال enabled وpressable وenter وslideY وspring وtrack وrelease إلى Motion.java.

## قواعد المشروع
- لا ملفات README.
- لا تعليقات أو نصوص عربية داخل ملفات الكود (النصوص الظاهرة للمستخدم في strings.xml فقط).
