// ────────────────────────────────────────────────────────────────────────────
// training.jsx — Обучение (Training) tab + per-course detail
//
// Pharmacist learning paths. Each completed course pays out a small bonus
// (+150 / +200 / +400 ₸) to the user's PharmaPay balance.
//
// TrainingScreen layout:
//   • Green header — Logo + points-balance star pill on the right.
//   • Progress card (`bg-brand-green-700/50 backdrop-blur`) — graduation
//     glyph + «N из M курсов» + horizontal progress bar + earned line.
//   • Resume card — full-width white card with cover, play overlay, brand
//     pill, reward badge, lesson progress + percent.
//   • Category chips — «Все курсы», «Новые (n)», «Рецептурные (n)», etc.
//   • Course list — horizontal small-cards: 88×88 cover with play/check/lock
//     icon, brand pill, NEW/Done badge, title, duration · difficulty,
//     optional progress bar, right side: green reward + chevron.
//
// CourseDetail layout:
//   • 280-tall cover with back button + big play overlay.
//   • White content area (rounded top + -mt-4 overlap) with duration row,
//     reward callout, «Чему вы научитесь» bullets, lesson list, primary CTA
//     that switches between «Начать курс» / «Продолжить» / «Пройти ещё раз»
//     based on `course.progress`.
// ────────────────────────────────────────────────────────────────────────────

// Обучение (Training) — NEW tab. Pharmacist learning paths.
function TrainingScreen({ onOpenCourse }) {
  const { TRAINING_PROGRESS, TRAINING_CATEGORIES, TRAINING_COURSES } = window.PP_DATA;
  const [cat, setCat] = React.useState('all');
  const filtered = cat==='all' ? TRAINING_COURSES : TRAINING_COURSES.filter(c => c.id);

  return (
    <div className="screen absolute inset-0 bg-paper flex flex-col">
      <div className="grad-blueHead px-5 pt-1 pb-7 rounded-b-3xl relative overflow-hidden">
        <StatusBar time="9:41" dark/>
        <div className="mt-2 mb-4 flex items-center justify-between">
          <Logo size="md"/>
          <button className="glass-pill h-9 px-3 rounded-full flex items-center gap-1 text-white text-[12px] font-bold">
            <I.Star size={14}/> {TRAINING_PROGRESS.points} б.
          </button>
        </div>

        {/* Progress card */}
        <div className="bg-brand-green-700/50 rounded-3xl p-4 backdrop-blur-md border border-white/10 text-white">
          <div className="flex items-center gap-3">
            <div className="w-14 h-14 rounded-2xl bg-white/15 grid place-items-center"><I.Grad size={28}/></div>
            <div className="flex-1">
              <div className="text-[14px] font-medium opacity-85">Ваш прогресс</div>
              <div className="text-[22px] font-extrabold leading-tight">{TRAINING_PROGRESS.completed} из {TRAINING_PROGRESS.total} курсов</div>
            </div>
          </div>
          {/* progress bar */}
          <div className="mt-4 h-2 rounded-full bg-white/15 overflow-hidden">
            <div className="h-full bg-brand-blue-500 rounded-full" style={{width: `${(TRAINING_PROGRESS.completed/TRAINING_PROGRESS.total)*100}%`}}/>
          </div>
          <div className="mt-2 flex items-center justify-between text-[12px] font-medium opacity-90">
            <span>Заработано: <b className="font-extrabold">{TRAINING_PROGRESS.points} ₸ бонусов</b></span>
            <span>+200 ₸ за курс</span>
          </div>
        </div>
      </div>

      {/* body */}
      <div className="flex-1 overflow-y-auto no-scrollbar pb-[120px]">
        {/* Resume card */}
        <div className="px-5 mt-4">
          <div className="text-[12px] font-bold uppercase tracking-wider text-ink-400 mb-2">Продолжить</div>
          <ResumeCard course={TRAINING_COURSES.find(c=>c.progress>0 && c.progress<100)} onClick={onOpenCourse}/>
        </div>

        {/* categories pills */}
        <div className="mt-5 px-5 flex gap-2 overflow-x-auto no-scrollbar pb-1">
          <FilterChip active={cat==='all'} onClick={()=>setCat('all')}>Все курсы</FilterChip>
          {TRAINING_CATEGORIES.map(c => (
            <FilterChip key={c.id} active={cat===c.id} onClick={()=>setCat(c.id)}>
              {c.label} <span className={`ml-1 text-[12px] ${cat===c.id?'opacity-90':'opacity-60'}`}>{c.count}</span>
            </FilterChip>
          ))}
        </div>

        {/* course list */}
        <div className="mt-3 px-5 flex flex-col gap-3">
          {filtered.map(c => <CourseCard key={c.id} course={c} onClick={onOpenCourse}/>)}
        </div>
      </div>
    </div>
  );
}

function ResumeCard({ course, onClick }) {
  if (!course) return null;
  return (
    <button onClick={()=>onClick(course)} className="w-full text-left bg-white rounded-2xl shadow-card overflow-hidden active:scale-[0.995] transition-transform">
      <div className="h-[140px] relative grid place-items-center" style={{background: course.cover}}>
        <div className="absolute inset-0 bg-gradient-to-tr from-black/30 to-transparent"/>
        <div className="relative w-14 h-14 rounded-full bg-white/95 grid place-items-center text-brand-green-600 shadow-lg">
          <I.Play size={28}/>
        </div>
        <div className="absolute top-3 left-3 bg-white/95 px-2 py-1 rounded-md text-[11px] font-bold text-ink-900 whitespace-nowrap">{course.brand}</div>
        <div className="absolute top-3 right-3 bg-brand-blue-500 px-2 py-1 rounded-md text-[11px] font-extrabold text-white whitespace-nowrap">+{course.reward} ₸</div>
      </div>
      <div className="p-4">
        <div className="text-[16px] font-extrabold text-ink-900 leading-tight">{course.title}</div>
        <div className="mt-2 flex items-center justify-between">
          <div className="text-[12px] font-semibold text-ink-500">Урок {Math.round(course.progress/100*course.lessons)} из {course.lessons}</div>
          <div className="text-[12px] font-bold text-brand-green-600">{course.progress}%</div>
        </div>
        <div className="mt-1.5 h-1.5 rounded-full bg-paper-input">
          <div className="h-full bg-brand-green-600 rounded-full" style={{width: `${course.progress}%`}}/>
        </div>
      </div>
    </button>
  );
}

function CourseCard({ course, onClick }) {
  return (
    <button onClick={()=>onClick(course)}
      className={`relative bg-white rounded-2xl shadow-card p-3 flex items-center gap-3 text-left active:scale-[0.99] transition-transform ${course.locked?'opacity-80':''}`}>
      <div className="w-[88px] h-[88px] rounded-xl shrink-0 grid place-items-center text-white relative overflow-hidden" style={{background: course.cover}}>
        {course.locked ? <I.Lock size={26}/> : course.completed ? <I.Check size={26}/> : <I.Play size={26}/>}
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5 mb-1 flex-wrap">
          <span className="text-[11px] font-bold text-ink-500 bg-paper-input rounded-md px-1.5 py-0.5">{course.brand}</span>
          {course.new && <span className="text-[11px] font-extrabold text-brand-blue-500 bg-brand-blue-500/10 rounded-md px-1.5 py-0.5">NEW</span>}
          {course.completed && <span className="text-[11px] font-extrabold text-brand-blue-600 bg-brand-blue-600/10 rounded-md px-1.5 py-0.5">Пройдено</span>}
        </div>
        <div className="text-[15px] font-extrabold text-ink-900 leading-tight line-clamp-2">{course.title}</div>
        <div className="mt-1.5 flex items-center gap-3 text-[12px] font-semibold text-ink-500">
          <span className="flex items-center gap-1"><I.Clock size={14}/>{course.duration}</span>
          <span>·</span>
          <span>{course.difficulty}</span>
        </div>
        {course.progress>0 && course.progress<100 && (
          <div className="mt-2 h-1 rounded-full bg-paper-input">
            <div className="h-full bg-brand-green-600 rounded-full" style={{width: `${course.progress}%`}}/>
          </div>
        )}
      </div>
      <div className="shrink-0 text-right">
        <div className="text-[14px] font-extrabold text-brand-blue-500 leading-tight whitespace-nowrap">+{course.reward}<span className="text-[11px] ml-0.5">₸</span></div>
        <I.Chevron className="text-ink-400 mt-1 ml-auto"/>
      </div>
    </button>
  );
}

// Course detail (opens from list)
function CourseDetail({ course, onBack, onComplete }) {
  if (!course) return null;
  return (
    <div className="screen absolute inset-0 bg-paper flex flex-col">
      <div className="relative h-[280px] overflow-hidden" style={{background: course.cover}}>
        <StatusBar time="9:41" dark/>
        <div className="px-5 mt-1 flex items-center gap-3 text-white">
          <button onClick={onBack} className="w-10 h-10 rounded-full bg-black/20 grid place-items-center"><I.Back/></button>
        </div>
        <div className="absolute inset-0 grid place-items-center pointer-events-none">
          <div className="w-24 h-24 rounded-full bg-white/90 grid place-items-center text-brand-green-600 shadow-xl"><I.Play size={48}/></div>
        </div>
        <div className="absolute bottom-4 left-5 right-5 text-white">
          <div className="text-[12px] font-bold opacity-90">{course.brand}</div>
          <div className="text-[22px] font-extrabold leading-tight mt-1">{course.title}</div>
        </div>
      </div>
      <div className="flex-1 overflow-y-auto no-scrollbar bg-white -mt-4 rounded-t-3xl px-5 pt-5 pb-[120px]">
        <div className="flex items-center gap-3 text-[13px] font-semibold text-ink-500">
          <span className="flex items-center gap-1"><I.Clock size={16}/>{course.duration}</span>
          <span>·</span>
          <span>{course.difficulty}</span>
          <span>·</span>
          <span>{course.lessons} уроков</span>
        </div>
        <div className="mt-4 p-4 bg-brand-green-100 rounded-2xl flex items-center gap-3">
          <I.GiftEmoji size={36}/>
          <div>
            <div className="text-[14px] font-extrabold text-ink-900">Награда за прохождение</div>
            <div className="text-[13px] font-medium text-ink-500">+{course.reward} ₸ на баланс PharmaPay</div>
          </div>
        </div>
        <h3 className="mt-5 text-[18px] font-extrabold text-ink-900">Чему вы научитесь</h3>
        <ul className="mt-2 flex flex-col gap-2 text-[14px] font-medium text-ink-700">
          {['Фармакологическое действие и механизм', 'Показания и противопоказания', 'Правильный подбор дозировки', 'Работа с возражениями клиента'].map((s,i)=>(
            <li key={i} className="flex items-start gap-2">
              <div className="w-5 h-5 rounded-full bg-brand-blue-500/15 text-brand-blue-600 grid place-items-center mt-0.5"><I.Check size={14}/></div>
              {s}
            </li>
          ))}
        </ul>
        <h3 className="mt-5 text-[18px] font-extrabold text-ink-900">Уроки</h3>
        <div className="mt-2 flex flex-col gap-2">
          {[1,2,3,4].map(n => (
            <div key={n} className="flex items-center gap-3 p-3 rounded-2xl bg-paper-input">
              <div className="w-9 h-9 rounded-full bg-white grid place-items-center text-brand-green-600 font-extrabold">{n}</div>
              <div className="flex-1">
                <div className="text-[14px] font-bold text-ink-900">Урок {n}: {['Введение','Фармакология','Показания','Возражения'][n-1]}</div>
                <div className="text-[12px] font-medium text-ink-500">2 мин · теория + тест</div>
              </div>
              {n <= Math.round(course.progress/100*course.lessons) ? <I.Check className="text-brand-blue-600"/> : <I.Play className="text-ink-400" size={18}/>}
            </div>
          ))}
        </div>
        <div className="mt-5">
          <ButtonPrimary variant="blue" onClick={onComplete}>{course.progress===0 ? 'Начать курс' : course.progress===100 ? 'Пройти ещё раз' : 'Продолжить'}</ButtonPrimary>
        </div>
      </div>
    </div>
  );
}

window.TrainingScreen = TrainingScreen;
window.CourseDetail = CourseDetail;
