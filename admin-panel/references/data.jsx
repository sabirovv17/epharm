// All fixtures for the admin console. Realistic Kazakhstani / pharma data.
// Lives on window.AD.

const fmt = (n) => new Intl.NumberFormat('ru-RU').format(n);
const fmtKzt = (n) => fmt(n) + ' ₸';

const SECTIONS = [
  { id:'dashboard',  label:'Дашборд аналитики', group:'Обзор',     Icon: IconDashboard },
  { id:'promo',      label:'Промо-кампании',    group:'Кампании',  Icon: IconPromo },
  { id:'rules',      label:'Rules Engine',      group:'Кампании',  Icon: IconRules, badge:'12' },
  { id:'screens',    label:'Управление экранами', group:'Кампании', Icon: IconScreens },
  { id:'pharmacies', label:'Сеть аптек',        group:'Сеть',      Icon: IconPharmacy, badge:'507' },
  { id:'pharmacists',label:'Фармацевты',        group:'Сеть',      Icon: IconPharmacist },
  { id:'reconcile',  label:'Сверка чеков',      group:'Операции',  Icon: IconReconcile, badge:'24' },
  { id:'ai_exam',    label:'AI-Экзаменация',    group:'Операции',  Icon: IconAIExam },
  { id:'finance',    label:'Финансы / выплаты', group:'Операции',  Icon: IconFinance, badge:'!' },
  { id:'lift',       label:'Аналитика lift',    group:'Аналитика', Icon: IconLift },
  { id:'lms',        label:'Обучение / LMS',    group:'Аналитика', Icon: IconLMS },
  { id:'settings',   label:'Настройки',         group:'Система',   Icon: IconSettings },
];

const USERS = {
  damir: { id:'damir', name:'Дамир Нурланов', role:'Brand manager', company:'Jadran', avatar:null },
  aigerim: { id:'aigerim', name:'Айгерим Сарсенова', role:'Category Lead', company:'Inkar / Ledex', avatar:null },
  bauyrzhan: { id:'bauyrzhan', name:'Бауыржан Тлеуов', role:'Head of Trade Marketing', company:'Inkar', avatar:null },
};

// ─── Rules Engine ─────────────────────────────────────────────────────────

const PRODUCT_LIBRARY = [
  // Jadran (Damir's brands)
  { id:'p_aqm_norm_s',  brand:'Jadran-Galenski', name:'Аквамарис Норм спрей 30мл', mnn:'Морская вода',  price:1480, vendor:'Jadran' },
  { id:'p_aqm_norm_d',  brand:'Jadran-Galenski', name:'Аквамарис Норм капли 10мл', mnn:'Морская вода',  price:980,  vendor:'Jadran' },
  { id:'p_aqm_strong',  brand:'Jadran-Galenski', name:'Аквамарис Стронг 30мл',     mnn:'Морская вода',  price:1820, vendor:'Jadran' },
  { id:'p_aqm_baby',    brand:'Jadran-Galenski', name:'Аквамарис Беби спрей 50мл', mnn:'Морская вода',  price:2240, vendor:'Jadran' },
  // Competitors
  { id:'p_aql_norm_s',  brand:'Aurena Labs',     name:'Аквалор Норм спрей 50мл',   mnn:'Морская вода',  price:1620, vendor:'Aurena' },
  { id:'p_aql_baby_s',  brand:'Aurena Labs',     name:'Аквалор Беби спрей 50мл',   mnn:'Морская вода',  price:2380, vendor:'Aurena' },
  { id:'p_rino_aqua',   brand:'GlaxoSmithKline', name:'Риномарис аква 30мл',       mnn:'Морская вода',  price:1240, vendor:'GSK' },
  // Other categories — generics for cross-sell
  { id:'p_otri_0_05',   brand:'Novartis',        name:'Отривин Бэби 0.05% 10мл',   mnn:'Ксилометазолин',price:1380, vendor:'Novartis' },
  { id:'p_nazi_0_1',    brand:'Bayer',           name:'Називин 0.1% 10мл',         mnn:'Оксиметазолин', price:1720, vendor:'Bayer' },
  { id:'p_iliad',       brand:'Polpharma',       name:'Илиадин 0.05% спрей 10мл',  mnn:'Оксиметазолин', price:1480, vendor:'Polpharma' },
  // Cross-sell candidates
  { id:'p_pinos',       brand:'Jadran-Galenski', name:'Пиносол спрей назальный 10мл', mnn:'Эфирные масла', price:1820, vendor:'Jadran' },
  { id:'p_septolete',   brand:'KRKA',            name:'Септолете нео леденцы',     mnn:'Цетилпиридиний',price:1640, vendor:'KRKA' },
  { id:'p_strepsils',   brand:'Reckitt',         name:'Стрепсилс ментол №24',      mnn:'Амилметакрезол',price:2120, vendor:'Reckitt' },
];

const productById = (id) => PRODUCT_LIBRARY.find(p => p.id === id);

const SPARK_HIGH = [22,26,31,28,34,38,42,40,46,52,49,58,62,60,68];
const SPARK_MED  = [12,14,11,18,16,22,19,24,28,26,32,30,35,38,36];
const SPARK_LOW  = [4,5,3,7,6,5,8,7,9,8,10,12,11,13,14];
const SPARK_FLAT = [18,17,19,18,20,19,21,20,22,21,23,22,24,23,25];

const RULES_SUBST = [
  {
    id:'r_001',
    type:'substitution',
    status:'active',
    trigger:{ kind:'product', value:'p_aql_norm_s' },
    recommend:'p_aqm_norm_s',
    bonus: 520,
    impressions: 847,
    accepts: 312,
    convRate: 36.8,
    revenue: 461_640,
    payout: 162_240,
    script:'«Аквамарис изотоничен и подходит для ежедневной гигиены. Сейчас на него действует бонус для аптеки — 520 ₸ за упаковку.»',
    advantages:['Изотонический раствор (0.9%)','Не вызывает привыкания','Бонус фармацевту 520 ₸'],
    abTest:null,
    createdBy:'damir',
    updatedAt:'2026-05-18',
    spark:SPARK_HIGH,
    pharmacies: 247,
  },
  {
    id:'r_002',
    type:'substitution',
    status:'active',
    trigger:{ kind:'product', value:'p_aql_baby_s' },
    recommend:'p_aqm_baby',
    bonus: 680,
    impressions: 524,
    accepts: 188,
    convRate: 35.9,
    revenue: 421_120,
    payout: 127_840,
    script:'«Беби-серия Аквамарис специально разработана для детей от 3 месяцев. С 1 мая бонус 680 ₸ за упаковку.»',
    advantages:['Безопасно с 3 месяцев','Анатомическая насадка-ограничитель','Бонус 680 ₸'],
    abTest:{ variant:'B', share:50 },
    createdBy:'damir',
    updatedAt:'2026-05-15',
    spark:SPARK_MED,
    pharmacies: 198,
  },
  {
    id:'r_003',
    type:'substitution',
    status:'active',
    trigger:{ kind:'mnn', value:'Морская вода', exclude:['p_aqm_norm_s','p_aqm_baby','p_aqm_norm_d','p_aqm_strong'] },
    recommend:'p_aqm_norm_s',
    bonus: 420,
    impressions: 1240,
    accepts: 421,
    convRate: 33.9,
    revenue: 623_080,
    payout: 176_820,
    script:'«Любой препарат на основе морской воды можно заменить на Аквамарис — действующее вещество идентично, а сегодня действует акция.»',
    advantages:['Любая МНН-группа «морская вода»','Маржа аптеки выше на 18%','Бонус 420 ₸'],
    abTest:null,
    createdBy:'damir',
    updatedAt:'2026-05-12',
    spark:SPARK_MED,
    pharmacies: 312,
  },
  {
    id:'r_004',
    type:'substitution',
    status:'paused',
    trigger:{ kind:'product', value:'p_rino_aqua' },
    recommend:'p_aqm_norm_d',
    bonus: 380,
    impressions: 218,
    accepts: 52,
    convRate: 23.9,
    revenue: 50_960,
    payout: 19_760,
    script:'«Альтернатива в каплях — Аквамарис Норм 10 мл. Бонус 380 ₸.»',
    advantages:['Капельная форма','Бонус 380 ₸'],
    abTest:null,
    createdBy:'damir',
    updatedAt:'2026-05-08',
    spark:SPARK_LOW,
    pharmacies: 64,
  },
];

const RULES_CROSS = [
  {
    id:'r_101',
    type:'crosssell',
    status:'active',
    trigger:{ kind:'mnn', value:'Ксилометазолин|Оксиметазолин' },
    recommend:'p_pinos',
    bonus: 320,
    impressions: 612,
    accepts: 174,
    convRate: 28.4,
    revenue: 316_680,
    payout: 55_680,
    script:'«К сосудосуживающему рекомендуйте Пиносол — натуральные эфирные масла, восстанавливает слизистую после капель. Бонус 320 ₸.»',
    advantages:['Натуральный состав','Восстанавливает слизистую','Совместим с любыми каплями','Бонус 320 ₸'],
    abTest:null,
    createdBy:'damir',
    updatedAt:'2026-05-10',
    spark:SPARK_MED,
    pharmacies: 184,
  },
  {
    id:'r_102',
    type:'crosssell',
    status:'active',
    trigger:{ kind:'product_any', value:['p_aqm_norm_s','p_aqm_strong','p_aqm_baby'] },
    recommend:'p_pinos',
    bonus: 280,
    impressions: 1124,
    accepts: 248,
    convRate: 22.1,
    revenue: 451_360,
    payout: 69_440,
    script:'«К Аквамарису предложите Пиносол — закрывают разные задачи: гигиена + восстановление. Аптека получает оба бонуса.»',
    advantages:['Дополняющий продукт','Двойной бонус','Бонус 280 ₸'],
    abTest:null,
    createdBy:'damir',
    updatedAt:'2026-05-04',
    spark:SPARK_HIGH,
    pharmacies: 286,
  },
];

const RULES_ARCHIVE = [
  { id:'r_a1', type:'substitution', status:'archived', trigger:{kind:'product',value:'p_aql_norm_s'}, recommend:'p_aqm_strong', bonus:450, impressions:120, accepts:18, convRate:15, revenue:0, payout:0, script:'(старая версия скрипта)', advantages:[], createdBy:'damir', updatedAt:'2026-04-22', spark:SPARK_LOW, pharmacies:42 },
  { id:'r_a2', type:'substitution', status:'archived', trigger:{kind:'mnn',value:'Морская вода'}, recommend:'p_aqm_baby', bonus:320, impressions:64,  accepts:8,  convRate:12.5, revenue:0, payout:0, script:'(сезонная)', advantages:[], createdBy:'damir', updatedAt:'2026-04-02', spark:SPARK_LOW, pharmacies:18 },
  { id:'r_a3', type:'crosssell',    status:'archived', trigger:{kind:'product',value:'p_aqm_strong'}, recommend:'p_septolete', bonus:240, impressions:88,  accepts:14, convRate:15.9, revenue:0, payout:0, script:'(кросс-сейл с леденцами)', advantages:[], createdBy:'damir', updatedAt:'2026-03-18', spark:SPARK_LOW, pharmacies:24 },
  { id:'r_a4', type:'substitution', status:'archived', trigger:{kind:'product',value:'p_rino_aqua'}, recommend:'p_aqm_norm_s', bonus:280, impressions:42,  accepts:6,  convRate:14.3, revenue:0, payout:0, script:'(тест март)', advantages:[], createdBy:'damir', updatedAt:'2026-03-04', spark:SPARK_LOW, pharmacies:12 },
];

const CONTRACT = {
  brand: 'Jadran-Galenski',
  brands: ['Аквамарис','Пиносол','Беби-серия','Стронг-серия'],
  brandsCount: 4,
  period: '01 мая — 31 мая 2026',
  budgetTotal: 3_000_000,
  budgetUsed:  1_842_300,
  approvedBy: 'aigerim',
  signedAt: '2026-04-28',
};

// ─── Pharmacies network ───────────────────────────────────────────────────

const CHAINS = [
  { id:'europharma', name:'Europharma',   color:'#2A2BE2', points:142, group:'pilot' },
  { id:'sadyhan',    name:'Садыхан',      color:'#16C97A', points:88,  group:'rolled' },
  { id:'zerde',      name:'Зерде',        color:'#F4B73A', points:76,  group:'pilot' },
  { id:'biosfera',   name:'Биосфера',     color:'#8B5CF6', points:64,  group:'control' },
  { id:'kruglosut',  name:'Круглосуточная',color:'#E5484D',points:48,  group:'rolled' },
  { id:'farmis',     name:'Фармис',       color:'#0F8F55', points:42,  group:'pilot' },
  { id:'apteka_36',  name:'Аптека 36',    color:'#5560FB', points:38,  group:'control' },
  { id:'avicenna',   name:'Avicenna',     color:'#0F1424', points:9,   group:'pilot' },
];

const PHARMACY_LIST = (() => {
  const cities = ['Алматы','Астана','Шымкент','Караганда','Актобе','Атырау','Усть-Каменогорск','Павлодар'];
  const dist = ['Бостандыкский','Ауэзовский','Алмалинский','Медеуский','Турксибский','Жетысуский','Сарыаркинский'];
  const out = [];
  CHAINS.forEach(c => {
    for (let i=0; i<8; i++) {
      const city = cities[(i + c.points) % cities.length];
      out.push({
        id: `${c.id}_${i}`,
        name: `${c.name} №${100 + i*3}`,
        chain: c.name,
        chainId: c.id,
        city,
        district: dist[i % dist.length],
        addr: `ул. ${['Абая','Толе би','Сейфуллина','Достык','Назарбаева'][i % 5]}, ${10+i*7}`,
        group: c.group,
        pharmacists: 2 + (i % 4),
        receipts30d: 200 + (i*53) + c.points*4,
        gmv30d: (200 + i*53 + c.points*4) * (3200 + i*50),
        liftPct: c.group==='control' ? 0 : 8 + (i % 18),
        rulesAccepted: c.group==='control' ? 0 : 22 + (i*4) % 60,
        active: i % 7 !== 0,
      });
    }
  });
  return out;
})();

// ─── Pharmacists registry ─────────────────────────────────────────────────

const FIRST_M = ['Алмас','Бауыржан','Даурен','Ерлан','Жасулан','Канат','Мурат','Нурлан','Рустам','Тимур'];
const FIRST_F = ['Айгерим','Алия','Балжан','Гульнара','Динара','Жанар','Куралай','Мадина','Сауле','Толкын'];
const LAST    = ['Абдрахманова','Бекенов','Жумабаев','Касенова','Мухтарова','Нурланов','Оразов','Сатпаева','Турсунов','Ыбраев'];

const PHARMACISTS = Array.from({length: 48}, (_, i) => {
  const female = i % 5 !== 0;
  const fn = (female ? FIRST_F : FIRST_M)[i % 10];
  const ln = LAST[i % 10] + (female && !LAST[i%10].endsWith('а')? 'а' : '');
  const ph = PHARMACY_LIST[i % PHARMACY_LIST.length];
  const earned = 28_000 + ((i*4271) % 230_000);
  return {
    id:`u_${i}`,
    name:`${fn} ${ln}`,
    iin:`${950101 + i*101}123456`.slice(0,12),
    phone:`+7 (7${10 + (i%89)}) ${100 + (i%900)}-${10+i%89}-${20+i%79}`,
    pharmacy: ph.name,
    pharmacyId: ph.id,
    city: ph.city,
    tier: ['Silver','Gold','Platinum'][i % 3],
    receipts30d: 22 + (i*7) % 240,
    rulesAccepted30d: 4 + (i*3) % 60,
    earned30d: earned,
    balance: earned - ((i*1700) % earned),
    coursesDone: i % 7,
    coursesTotal: 9,
    status: i % 17 === 0 ? 'blocked' : i % 11 === 0 ? 'pending' : 'active',
    joinedAt:`2026-${String(1 + (i%5)).padStart(2,'0')}-${String(1+(i*3)%28).padStart(2,'0')}`,
  };
});

// ─── Finance: payouts ─────────────────────────────────────────────────────

const PAYOUT_BATCHES = [
  { id:'pb_2026_05_15', period:'1–15 мая 2026', status:'pending',  pharmacists: 312, amount: 4_180_400, reviewer:'aigerim', items:312 },
  { id:'pb_2026_04_30', period:'16–30 апреля 2026', status:'approved', pharmacists: 287, amount: 3_924_200, reviewer:'aigerim', approvedAt:'2026-05-02', items:287 },
  { id:'pb_2026_04_15', period:'1–15 апреля 2026',  status:'approved', pharmacists: 268, amount: 3_641_080, reviewer:'aigerim', approvedAt:'2026-04-17', items:268 },
  { id:'pb_2026_03_30', period:'16–31 марта 2026',  status:'approved', pharmacists: 254, amount: 3_488_240, reviewer:'aigerim', approvedAt:'2026-04-02', items:254 },
];

const PAYOUT_ITEMS = PHARMACISTS.slice(0, 18).map((p,i) => ({
  id:`pi_${p.id}`, pharmacist:p.name, pharmacy:p.pharmacy, city:p.city,
  receipts: 14 + (i*3) % 40,
  rules: 6 + (i*2) % 28,
  amount: 8_400 + ((i*2317) % 38_000),
  flag: i % 9 === 0 ? 'anomaly' : null,
}));

// ─── Reconcile (receipt review queue) ─────────────────────────────────────

const RECONCILE_QUEUE = [
  { id:'rc_001', uploaded:'2026-05-21 14:22', pharmacist:'Айгерим Сарсенова', pharmacy:'Europharma №112', total:18_240, products:['Аквамарис Норм спрей','Пиносол спрей'], status:'pending', score:0.92, flag:null },
  { id:'rc_002', uploaded:'2026-05-21 14:18', pharmacist:'Динара Касенова',   pharmacy:'Садыхан №103',    total:6_820,  products:['Аквамарис Беби'], status:'pending', score:0.97, flag:null },
  { id:'rc_003', uploaded:'2026-05-21 14:11', pharmacist:'Канат Бекенов',     pharmacy:'Зерде №118',      total:1_480,  products:['Аквамарис Норм спрей'], status:'flagged', score:0.42, flag:'low_ocr' },
  { id:'rc_004', uploaded:'2026-05-21 13:48', pharmacist:'Мадина Турсунова',  pharmacy:'Биосфера №109',   total:24_180, products:['Аквамарис Стронг','Пиносол','Аквамарис Беби'], status:'pending', score:0.88, flag:null },
  { id:'rc_005', uploaded:'2026-05-21 13:32', pharmacist:'Жасулан Оразов',    pharmacy:'Круглосуточная №115', total:3_640, products:['Аквамарис Норм'], status:'flagged', score:0.31, flag:'duplicate' },
  { id:'rc_006', uploaded:'2026-05-21 12:55', pharmacist:'Балжан Сатпаева',   pharmacy:'Europharma №106', total:9_120,  products:['Аквамарис Беби','Аквамарис Норм спрей'], status:'approved', score:0.99, flag:null },
  { id:'rc_007', uploaded:'2026-05-21 12:40', pharmacist:'Нурлан Жумабаев',   pharmacy:'Фармис №108',     total:4_280,  products:['Аквамарис Стронг'], status:'approved', score:0.95, flag:null },
  { id:'rc_008', uploaded:'2026-05-21 12:22', pharmacist:'Толкын Мухтарова',  pharmacy:'Avicenna №101',   total:1_820,  products:['Аквамарис Стронг'], status:'rejected', score:0.18, flag:'no_brand' },
];

// ─── AI Exam ──────────────────────────────────────────────────────────────

const AI_EXAM_BANK = [
  { id:'q1', topic:'Аквамарис Норм', q:'Какое действующее вещество в Аквамарис Норм?', a:'Изотонический раствор морской воды (0.9%)', difficulty:'easy', passed:0.94 },
  { id:'q2', topic:'Аквамарис Беби', q:'С какого возраста можно применять Аквамарис Беби?', a:'С 3 месяцев', difficulty:'easy', passed:0.91 },
  { id:'q3', topic:'Замена', q:'Можно ли заменить Аквалор Норм на Аквамарис Норм?', a:'Да, идентичный состав, действующее вещество — морская вода 0.9%.', difficulty:'medium', passed:0.78 },
  { id:'q4', topic:'Кросс-сейл', q:'Что предложить в дополнение к сосудосуживающим каплям?', a:'Пиносол — для восстановления слизистой после ксилометазолина.', difficulty:'medium', passed:0.62 },
  { id:'q5', topic:'Скрипт', q:'Назовите три преимущества Аквамарис Норм при разговоре с покупателем.', a:'Изотонический, не вызывает привыкания, для ежедневной гигиены.', difficulty:'hard', passed:0.48 },
];

const AI_EXAM_RESULTS = PHARMACISTS.slice(0, 24).map((p,i) => ({
  id:p.id, name:p.name, pharmacy:p.pharmacy,
  attempts: 1 + (i % 3),
  score: 60 + ((i*7) % 40),
  passed: ((i*7) % 100) >= 35,
  bonus: ((i*7) % 100) >= 35 ? 800 : 0,
  takenAt: `2026-05-${String(1 + (i % 20)).padStart(2,'0')}`,
}));

// ─── Promo campaigns ──────────────────────────────────────────────────────

const PROMOS = [
  { id:'pr_001', title:'Майский марафон Аквамарис', status:'active', brand:'Jadran-Galenski', period:'01.05 — 31.05', pharmacies:312, budget:3_000_000, spent:1_842_300, kpi:'14 820 рекомендаций', cover:'#16C97A' },
  { id:'pr_002', title:'Пиносол × Аквамарис: кросс', status:'active', brand:'Jadran-Galenski', period:'15.05 — 15.06', pharmacies:286, budget:1_200_000, spent:418_440, kpi:'2 240 cross-sell', cover:'#3DCDA2' },
  { id:'pr_003', title:'Конкурс «Лучший фармацевт»',status:'active', brand:'Jadran-Galenski', period:'01.05 — 31.05', pharmacies:507, budget:600_000, spent:200_000, kpi:'48 участников', cover:'#F4B73A' },
  { id:'pr_004', title:'Аквамарис Беби — летний сезон', status:'draft', brand:'Jadran-Galenski', period:'01.06 — 31.08', pharmacies:0, budget:4_500_000, spent:0, kpi:'-', cover:'#5560FB' },
  { id:'pr_005', title:'Стронг-серия H1', status:'paused', brand:'Jadran-Galenski', period:'01.04 — 30.04', pharmacies:142, budget:1_800_000, spent:1_624_000, kpi:'8 940 рекомендаций', cover:'#2A2BE2' },
];

// ─── Lift analytics (pilot vs control) ────────────────────────────────────

const LIFT_DATA = {
  pilot:   [120, 132, 128, 145, 158, 162, 175, 184, 192, 198, 210, 218],
  control: [120, 124, 122, 128, 130, 133, 135, 138, 141, 140, 143, 145],
  weeks: 12,
  liftPct: 50.4,
  pValue: 0.002,
  pilotN: 168, controlN: 168,
};

// ─── Screens (DOOH playlists) ─────────────────────────────────────────────

const SCREEN_PLAYLISTS = [
  { id:'pl_main', name:'Главный плейлист — кассовая зона', duration:'2 мин 40 сек', items:8, screens:312, active:true },
  { id:'pl_baby', name:'Беби-зона / детские товары',       duration:'1 мин 50 сек', items:5, screens:184, active:true },
  { id:'pl_cold', name:'Сезон ОРВИ (зима)',                duration:'3 мин 10 сек', items:9, screens:0,   active:false },
];

const SCREEN_SLIDES = [
  { id:'sl_1', title:'Аквамарис Норм — для всей семьи', duration:20, type:'video',  cover:'#16C97A' },
  { id:'sl_2', title:'Промо: Беби-серия',               duration:15, type:'image',  cover:'#5560FB' },
  { id:'sl_3', title:'Видеоролик: «как промывать нос»', duration:35, type:'video',  cover:'#3DCDA2' },
  { id:'sl_4', title:'Пиносол × Аквамарис кросс',       duration:20, type:'image',  cover:'#F4B73A' },
  { id:'sl_5', title:'Конкурс «Лучший фармацевт»',      duration:25, type:'image',  cover:'#8B5CF6' },
];

// ─── LMS courses ──────────────────────────────────────────────────────────

const LMS_COURSES = [
  { id:'c_aqm_basic', title:'Линейка Аквамарис: основы',     lessons:6, duration:'24 мин', students:412, completed:284, bonus:300, status:'published', updatedAt:'2026-05-10' },
  { id:'c_baby_safe', title:'Беби-серия: безопасность',      lessons:4, duration:'14 мин', students:218, completed:182, bonus:200, status:'published', updatedAt:'2026-05-04' },
  { id:'c_pino',      title:'Пиносол: натуральные масла',    lessons:5, duration:'18 мин', students:188, completed:124, bonus:250, status:'published', updatedAt:'2026-04-22' },
  { id:'c_subst',     title:'Скрипты замены: как говорить',  lessons:7, duration:'28 мин', students:402, completed:312, bonus:400, status:'published', updatedAt:'2026-05-12' },
  { id:'c_cross',     title:'Cross-sell: 5 правил',          lessons:5, duration:'16 мин', students:184, completed:88,  bonus:300, status:'draft',     updatedAt:'2026-05-20' },
];

// ─── Dashboard heatmap weeks ──────────────────────────────────────────────
const HEATMAP = Array.from({length: 12}, (_, w) => Array.from({length: 7}, (_, d) => Math.floor(Math.random() * 5)));

window.AD = {
  fmt, fmtKzt,
  SECTIONS, USERS, CONTRACT,
  PRODUCT_LIBRARY, productById,
  RULES_SUBST, RULES_CROSS, RULES_ARCHIVE,
  CHAINS, PHARMACY_LIST,
  PHARMACISTS,
  PAYOUT_BATCHES, PAYOUT_ITEMS,
  RECONCILE_QUEUE,
  AI_EXAM_BANK, AI_EXAM_RESULTS,
  PROMOS,
  LIFT_DATA,
  SCREEN_PLAYLISTS, SCREEN_SLIDES,
  LMS_COURSES,
  HEATMAP,
};
