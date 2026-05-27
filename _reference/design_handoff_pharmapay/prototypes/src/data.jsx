// ────────────────────────────────────────────────────────────────────────────
// data.jsx — Static fixtures for the prototype
//
// Mirrors the shape the production API should return. Three collections
// drive the catalog and training screens:
//
//   PROMOS              — promo carousel cards on Home (info / huggies / kotex)
//   PRODUCTS            — catalog tiles. `featured: true` → renders as the
//                         BigProductCard at the top of the list. `new` and
//                         `contest` flags drive the Новинки / Конкурсные chips.
//                         Tier ladders + bonus rows are inlined per product.
//   BRANDS              — the picker list inside the Brand sheet.
//   SORT_OPTIONS        — the radio rows in the Sort sheet.
//   PROFILE_HELP /      — list-row sections inside Профиль («Помощь» and
//   PROFILE_ABOUT          «О приложении»).
//   TRAINING_PROGRESS / — progress card, category chips and course cards on
//   TRAINING_CATEGORIES /  the Обучение tab.
//   TRAINING_COURSES
//
// Everything is published on `window.PP_DATA` so any sibling screen
// script can read it without an import.
// ────────────────────────────────────────────────────────────────────────────

// Static data for the prototype: promos, products, brands, profile items.
const PROMOS = [
  {
    id: 'p1',
    title: 'Важная информация',
    subtitle: 'Проведена плановая проверка транзакций за 2025 год.',
    note: 'Подробная информация доступна на сайте.',
    kind: 'info',
    bg: 'linear-gradient(180deg, #FAF6E4 0%, #F2EEDA 100%)',
  },
  {
    id: 'p2',
    title: 'Huggies трусик-жөргектері мен',
    subtitle: 'Kotex-пен бірге 50 000 тг кепілді',
    period: '01.04.2026 – 31.05.2026',
    amount: '50000₸',
    footer: 'Гарантированно 50 000 тг вместе',
    kind: 'huggies',
    bg: 'linear-gradient(180deg, #F2E2BA 0%, #E2C28C 100%)',
  },
  {
    id: 'p3',
    title: 'Күн сайын',
    subtitle: '10 000 тг бонус',
    period: '01.04.2026 – 31.05.2026',
    amount: '10000₸',
    footer: 'На каждый день — с бонусом 10 000 тг',
    kind: 'kotex',
    bg: 'linear-gradient(180deg, #E94A4A 0%, #C5292B 100%)',
  },
];

const CHIPS = ['Бренд', 'Все', 'Новинки', 'Конкурсные'];

const BRANDS = [
  'Polpharma Santo', 'AIGP', 'Alpen Pharma', 'Alvogen', 'Asfarma',
  'Danhson', 'Danielli', 'Dincom', 'Glenmark', 'Haleon',
  'Health Rising', 'Kazbiotech', 'Kimberly Clark', 'Micfarm',
  'Novartis', 'Pfizer', 'Sanofi',
];

const SORT_OPTIONS = [
  'Сначала новые акции',
  'По названию (А–Я)',
  'По типу акции (по возрастанию)',
  'По типу акции (по убыванию)',
];

const PRODUCTS = [
  {
    id: 1, brand: 'AIGP', name: 'AIGP Лифта 10 мг №1 табл.',
    period: 'с 1–31 мая', bought: 0,
    tiers: [{ qty: 1, label: 'от 1 шт.', price: '500₸' },
            { qty: 10, label: 'от 10 шт.', price: '600₸' },
            { qty: 20, label: 'от 20 шт.', price: '700₸' }],
    bonuses: ['Бонус при достижении 2 порога: 900 тг', 'Бонус при достижении 3 порога: 1900 тг'],
    pkg: { bg: 'linear-gradient(135deg, #0E3C2A 0%, #1A6E4A 100%)', label: 'ЛИФТА 10 мг', sub: 'Тадалафил / Tadalafil', maker: 'ABDI IBRAHIM' },
    featured: true, contest: true,
  },
  {
    id: 2, brand: 'Natrol', name: 'Natrol Мелатонин 10 мг №60 быстрораст.',
    period: '500₸/уп', bought: null,
    tiers: [], bonuses: [], restrictions: 'Есть ограничения по городам и аптекам',
    pkg: { bg: 'linear-gradient(180deg, #F4F1ED 0%, #E8DDC4 100%)', label: 'NATROL', accent: '#8030A8' },
  },
  {
    id: 3, brand: 'Polpharma Santo', name: 'Аквадетрим витамин Д3 10мл 15000МЕ/мл',
    period: '300₸/уп', bought: null,
    tiers: [], bonuses: [], restrictions: 'Есть ограничения по аптекам',
    pkg: { bg: 'linear-gradient(180deg, #DCEEFF 0%, #6FB7F2 100%)', label: 'АкваДетрим', accent: '#1E70A8' },
    new: true,
  },
  {
    id: 4, brand: 'Polpharma Santo', name: 'Аквадетрим форте 2000 №30',
    period: '400₸/уп', bought: null,
    tiers: [], bonuses: [], restrictions: 'Есть ограничения по аптекам',
    pkg: { bg: 'linear-gradient(135deg, #EFE3FB 0%, #C18FE2 100%)', label: 'АкваДетрим Форте', accent: '#7B2DB0' },
    new: true,
  },
  {
    id: 5, brand: 'Alpen Pharma', name: 'Аллестил капли для приема 1 мг/мл 20 мл',
    period: '250₸/уп', bought: null,
    tiers: [], bonuses: [], restrictions: 'Есть ограничения по аптекам',
    pkg: { bg: 'linear-gradient(180deg, #E4F5FA 0%, #9CD8E2 100%)', label: 'АЛЛЕСТИЛ', accent: '#1F8DA0' },
  },
  {
    id: 6, brand: 'Haleon', name: 'Витрум Витамин С 500 мг №60',
    period: 'с 1–31 мая', bought: null,
    tiers: [], bonuses: [], restrictions: 'Новинка месяца',
    pkg: { bg: 'linear-gradient(180deg, #FFF1D4 0%, #FFCB52 100%)', label: 'VITRUM C', accent: '#B26B0D' },
    new: true,
  },
  {
    id: 7, brand: 'Sanofi', name: 'Эссенциале форте Н №30 капс.',
    period: 'с 5–25 мая', bought: null,
    tiers: [], bonuses: [], restrictions: 'Новый бренд в каталоге',
    pkg: { bg: 'linear-gradient(180deg, #FBE3E3 0%, #C5292B 100%)', label: 'ЭССЕНЦИАЛЕ', accent: '#FFFFFF' },
    new: true,
  },
  {
    id: 8, brand: 'AIGP', name: 'AIGP Лифта 20 мг №4 табл.',
    period: 'с 1–31 мая', bought: 0,
    tiers: [{ qty: 1, label: 'от 1 шт.', price: '700₸' },
            { qty: 10, label: 'от 10 шт.', price: '900₸' },
            { qty: 20, label: 'от 20 шт.', price: '1100₸' }],
    bonuses: ['Доп. бонус +500 ₸ за 3 порог'],
    pkg: { bg: 'linear-gradient(135deg, #1A2C5C 0%, #2A6FDB 100%)', label: 'ЛИФТА 20 мг', sub: 'Тадалафил / Tadalafil', maker: 'ABDI IBRAHIM' },
    contest: true,
  },
  {
    id: 9, brand: 'Kimberly Clark', name: 'Huggies Elite Soft № 4',
    period: 'с 1–31 мая', bought: 0,
    tiers: [{ qty: 1, label: 'от 1 уп.', price: '1500₸' },
            { qty: 5, label: 'от 5 уп.', price: '1800₸' }],
    bonuses: ['Конкурс «Семейный кэшбэк» — приз 50 000 ₸'],
    pkg: { bg: 'linear-gradient(180deg, #F2E2BA 0%, #E2A14C 100%)', label: 'HUGGIES', accent: '#7E480A' },
    contest: true,
  },
];

const PROFILE_HELP = [
  { icon: 'Heart', label: 'Служба поддержки в WhatsApp' },
  { icon: 'Help', label: 'Вопросы и ответы' },
  { icon: 'Copy', label: 'Подробная инструкция' },
  { icon: 'DocCheck', label: 'Сотрудничество' },
];
const PROFILE_ABOUT = [
  { icon: 'Doc', label: 'Пользовательское соглашение' },
  { icon: 'Doc', label: 'Политика конфидициальности' },
];

// Training content (NEW tab)
const TRAINING_PROGRESS = { completed: 4, total: 12, points: 850 };
const TRAINING_CATEGORIES = [
  { id: 'new', label: 'Новые', count: 3 },
  { id: 'rx', label: 'Рецептурные', count: 5 },
  { id: 'otc', label: 'Безрецептурные', count: 7 },
  { id: 'vit', label: 'Витамины', count: 4 },
];

const TRAINING_COURSES = [
  {
    id: 't1', title: 'Лифта 10 мг — фармакология и показания',
    brand: 'AIGP', duration: '8 мин', reward: 200,
    progress: 0, lessons: 4, difficulty: 'Базовый',
    cover: 'linear-gradient(135deg, #16A65C 0%, #21D17A 100%)',
    new: true,
  },
  {
    id: 't2', title: 'Аквадетрим: подбор дозировки',
    brand: 'Polpharma Santo', duration: '12 мин', reward: 300,
    progress: 50, lessons: 6, difficulty: 'Средний',
    cover: 'linear-gradient(135deg, #2A2BE2 0%, #5560FB 100%)',
  },
  {
    id: 't3', title: 'Аллестил — работа с возражениями',
    brand: 'Alpen Pharma', duration: '6 мин', reward: 150,
    progress: 100, lessons: 3, difficulty: 'Базовый',
    cover: 'linear-gradient(135deg, #F4B73A 0%, #D69010 100%)',
    completed: true,
  },
  {
    id: 't4', title: 'Натрол мелатонин: режимы приёма',
    brand: 'Natrol', duration: '10 мин', reward: 250,
    progress: 25, lessons: 5, difficulty: 'Средний',
    cover: 'linear-gradient(135deg, #8030A8 0%, #4A1B6E 100%)',
  },
  {
    id: 't5', title: 'Огласие и противопоказания: чек-лист',
    brand: 'Общий курс', duration: '15 мин', reward: 400,
    progress: 0, lessons: 7, difficulty: 'Продвинутый',
    cover: 'linear-gradient(135deg, #C5292B 0%, #7E1112 100%)',
    locked: true,
    new: true,
  },
];

window.PP_DATA = {
  PROMOS, CHIPS, BRANDS, SORT_OPTIONS, PRODUCTS,
  PROFILE_HELP, PROFILE_ABOUT,
  TRAINING_PROGRESS, TRAINING_CATEGORIES, TRAINING_COURSES,
};
