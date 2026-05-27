// Настройки — workspace, roles, integrations, API.

function SettingsSection() {
  const [tab, setTab] = React.useState('workspace');
  const toast = useToast();

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Настройки" subtitle="Аккаунт, команда, интеграции, API"/>

      <div className="grid gap-5" style={{gridTemplateColumns:'220px minmax(0,1fr)'}}>
        <nav className="flex flex-col gap-1">
          {[
            { v:'workspace',   label:'Рабочее пространство', icon:<IconShield size={16}/> },
            { v:'team',        label:'Команда и роли',       icon:<IconUsers size={16}/> },
            { v:'brands',      label:'Бренды и контракты',   icon:<IconBox size={16}/> },
            { v:'integrations',label:'Интеграции',           icon:<IconLink size={16}/> },
            { v:'api',         label:'API и вебхуки',        icon:<IconCommand size={16}/> },
            { v:'billing',     label:'Биллинг',              icon:<IconFinance size={16}/> },
            { v:'audit',       label:'Журнал аудита',        icon:<IconHistory size={16}/> },
            { v:'danger',      label:'Опасная зона',         icon:<IconAlert size={16}/> },
          ].map(it => (
            <button key={it.v} onClick={()=>setTab(it.v)} className={`flex items-center gap-3 px-3 h-10 rounded-lg text-[14px] font-semibold transition ${tab===it.v?'bg-brand-green-50 text-brand-green-700':'text-ink-700 hover:bg-paper-hover'}`}>
              {it.icon}{it.label}
            </button>
          ))}
        </nav>

        <div className="flex flex-col gap-4">
          {tab === 'workspace' && (
            <SectionCard title="Рабочее пространство">
              <div className="grid grid-cols-2 gap-4">
                <Field label="Название"><Input defaultValue="Inkar HQ · Ledex Category"/></Field>
                <Field label="Часовой пояс"><Select value="Asia/Almaty" onChange={()=>{}} options={['Asia/Almaty','Asia/Aqtobe','UTC']}/></Field>
                <Field label="Валюта"><Select value="KZT" onChange={()=>{}} options={['KZT','USD','EUR']}/></Field>
                <Field label="Язык"><Select value="ru" onChange={()=>{}} options={[{value:'ru',label:'Русский'},{value:'kk',label:'Қазақша'}]}/></Field>
                <Field label="Логотип"><div className="card-soft p-4 flex items-center gap-3"><div className="w-14 h-14 rounded-xl bg-brand-green-100 flex items-center justify-center"><IconShield className="text-brand-green-700"/></div><Button variant="outline" size="sm" leading={<IconUpload size={14}/>}>Загрузить</Button></div></Field>
                <Field label="Контактный email"><Input defaultValue="hq@inkar.kz" type="email"/></Field>
              </div>
              <div className="mt-4 flex justify-end"><Button variant="primary" leading={<IconCheck size={14}/>} onClick={()=>toast.push('Настройки сохранены')}>Сохранить</Button></div>
            </SectionCard>
          )}

          {tab === 'team' && (
            <SectionCard title="Команда" subtitle="3 пользователя · роли управляются админом" padded={false}
              action={<Button variant="primary" size="sm" leading={<IconPlus size={14}/>}>Пригласить</Button>}>
              <table className="tbl">
                <thead><tr><th>Пользователь</th><th>Роль</th><th>Доступ к брендам</th><th>Последний вход</th><th/></tr></thead>
                <tbody>
                  {Object.values(AD.USERS).map(u => (
                    <tr key={u.id}>
                      <td><div className="flex items-center gap-3"><Avatar name={u.name} size={32}/><div><div className="font-extrabold">{u.name}</div><div className="text-[11px] text-ink-500 font-semibold">{u.id}@inkar.kz</div></div></div></td>
                      <td><span className="chip chip-blue">{u.role}</span></td>
                      <td><span className="font-bold">{u.company}</span></td>
                      <td className="text-[12px] text-ink-500 num">сегодня · 12:42</td>
                      <td><IconButton><IconDots size={14}/></IconButton></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </SectionCard>
          )}

          {tab === 'brands' && (
            <SectionCard title="Контракты брендов" subtitle="4 активных контракта" padded={false} action={<Button variant="primary" size="sm" leading={<IconPlus size={14}/>}>Новый контракт</Button>}>
              <table className="tbl">
                <thead><tr><th>Бренд</th><th>Период</th><th>Бюджет</th><th>Использовано</th><th>Brand manager</th><th>Статус</th></tr></thead>
                <tbody>
                  <tr><td className="font-extrabold">Jadran-Galenski</td><td>01.05 – 31.05.2026</td><td className="num">3 000 000 ₸</td><td><ProgressBar value={62} max={100}/><span className="text-[11px] text-ink-500 num">62%</span></td><td>Дамир Нурланов</td><td><StatusChip status="active"/></td></tr>
                  <tr><td className="font-extrabold">Polpharma Santo</td><td>15.05 – 15.06.2026</td><td className="num">2 400 000 ₸</td><td><ProgressBar value={28} max={100}/><span className="text-[11px] text-ink-500 num">28%</span></td><td>Айбек Мусин</td><td><StatusChip status="active"/></td></tr>
                  <tr><td className="font-extrabold">Sanofi</td><td>01.06 – 30.06.2026</td><td className="num">5 200 000 ₸</td><td><ProgressBar value={0} max={100}/><span className="text-[11px] text-ink-500 num">—</span></td><td>Ольга Ким</td><td><StatusChip status="pending"/></td></tr>
                  <tr><td className="font-extrabold">Reckitt</td><td>01.04 – 30.04.2026</td><td className="num">1 800 000 ₸</td><td><ProgressBar value={100} max={100}/><span className="text-[11px] text-ink-500 num">100%</span></td><td>Мария Тен</td><td><StatusChip status="archived"/></td></tr>
                </tbody>
              </table>
            </SectionCard>
          )}

          {tab === 'integrations' && (
            <SectionCard title="Интеграции">
              <div className="grid grid-cols-2 gap-3">
                {[
                  { name:'Kaspi Bank', desc:'Автоматические выплаты на карту', on:true, icon:'K', color:'#E5484D' },
                  { name:'Halyk Bank', desc:'Резервный канал выплат',          on:false, icon:'H', color:'#16C97A' },
                  { name:'1С Аптека',  desc:'Импорт справочников товаров',     on:true, icon:'1', color:'#F4B73A' },
                  { name:'Google Cloud Vision', desc:'OCR чеков',              on:true, icon:'G', color:'#5560FB' },
                  { name:'Telegram Bot', desc:'Уведомления brand manager-ам',  on:true, icon:'T', color:'#2A2BE2' },
                  { name:'SMS Aero',   desc:'OTP-коды для фармацевтов',        on:true, icon:'S', color:'#8B5CF6' },
                ].map(i => (
                  <div key={i.name} className="card-soft p-4 flex items-center gap-3">
                    <span className="w-10 h-10 rounded-xl flex items-center justify-center text-white font-extrabold" style={{background:i.color}}>{i.icon}</span>
                    <div className="flex-1">
                      <div className="text-[13px] font-extrabold">{i.name}</div>
                      <div className="text-[11px] text-ink-500 font-semibold">{i.desc}</div>
                    </div>
                    <Toggle on={i.on} onChange={()=>{}}/>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}

          {tab === 'api' && (
            <SectionCard title="API ключи" subtitle="Для server-to-server интеграций" padded={false} action={<Button variant="primary" size="sm" leading={<IconPlus size={14}/>}>Создать ключ</Button>}>
              <table className="tbl">
                <thead><tr><th>Имя</th><th>Ключ</th><th>Создан</th><th>Последнее использование</th><th/></tr></thead>
                <tbody>
                  <tr><td className="font-extrabold">Production · 1С</td><td className="font-mono text-[12px]">pk_live_••••••••72bA</td><td>2026-03-12</td><td>1 мин назад</td><td><IconButton><IconDots size={14}/></IconButton></td></tr>
                  <tr><td className="font-extrabold">Webhook · Kaspi</td><td className="font-mono text-[12px]">pk_live_••••••••8FXz</td><td>2026-02-04</td><td>12 мин назад</td><td><IconButton><IconDots size={14}/></IconButton></td></tr>
                  <tr><td className="font-extrabold">Staging</td><td className="font-mono text-[12px]">pk_test_••••••••gQ1m</td><td>2026-01-22</td><td>3 ч назад</td><td><IconButton><IconDots size={14}/></IconButton></td></tr>
                </tbody>
              </table>
            </SectionCard>
          )}

          {tab === 'billing' && (
            <>
              <SectionCard title="Подписка">
                <div className="flex items-center gap-4">
                  <div className="flex-1">
                    <div className="text-[12px] font-bold uppercase tracking-[0.06em] text-brand-green-700 mb-1">Текущий план</div>
                    <div className="text-[22px] font-extrabold">Enterprise · Inkar HQ</div>
                    <div className="text-[13px] text-ink-500 font-semibold">До 1 000 фармацевтов · 8 brand-менеджеров · безлимит правил</div>
                  </div>
                  <Button variant="outline">Сменить план</Button>
                </div>
              </SectionCard>
              <SectionCard title="История платежей" padded={false}>
                <table className="tbl">
                  <thead><tr><th>Период</th><th>Сумма</th><th>Способ</th><th>Статус</th><th/></tr></thead>
                  <tbody>
                    <tr><td>Май 2026</td><td className="num font-extrabold">480 000 ₸</td><td>Безналичный · Halyk</td><td><StatusChip status="approved"/></td><td><Button variant="ghost" size="sm">Скачать счёт</Button></td></tr>
                    <tr><td>Апрель 2026</td><td className="num font-extrabold">480 000 ₸</td><td>Безналичный · Halyk</td><td><StatusChip status="approved"/></td><td><Button variant="ghost" size="sm">Скачать счёт</Button></td></tr>
                    <tr><td>Март 2026</td><td className="num font-extrabold">480 000 ₸</td><td>Безналичный · Halyk</td><td><StatusChip status="approved"/></td><td><Button variant="ghost" size="sm">Скачать счёт</Button></td></tr>
                  </tbody>
                </table>
              </SectionCard>
            </>
          )}

          {tab === 'audit' && (
            <SectionCard title="Журнал действий" subtitle="Что и кто сделал в системе">
              <ul className="flex flex-col gap-2">
                {[
                  { who:'Дамир Нурланов', what:'Изменил правило r_001 «Аквалор Норм → Аквамарис Норм»', when:'5 мин назад', icon:<IconEdit size={14}/> },
                  { who:'Айгерим Сарсенова', what:'Утвердила выплаты за 16–30 апреля (3 924 200 ₸ · 287 фарм.)', when:'2 ч назад', icon:<IconCheck size={14}/> },
                  { who:'Дамир Нурланов', what:'Создал новое правило кросс-сейл r_102', when:'вчера 18:22', icon:<IconPlus size={14}/> },
                  { who:'Бауыржан Тлеуов', what:'Подписал контракт Jadran-Galenski на май', when:'4 дня назад', icon:<IconShield size={14}/> },
                  { who:'System', what:'Автоматически отклонил 12 дубликатов чеков', when:'4 дня назад', icon:<IconRefresh size={14}/> },
                ].map((a,i) => (
                  <li key={i} className="flex items-start gap-3 p-3 rounded-xl bg-paper-hover">
                    <Avatar name={a.who} size={28}/>
                    <div className="flex-1">
                      <div className="text-[13px]"><b>{a.who}</b> · {a.what}</div>
                      <div className="text-[11px] text-ink-500 font-semibold">{a.when}</div>
                    </div>
                    <span className="text-ink-400">{a.icon}</span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}

          {tab === 'danger' && (
            <SectionCard title="Опасная зона">
              <div className="flex flex-col gap-3">
                <div className="card-soft p-4 flex items-center justify-between border-amber-200/60 bg-amber-50/40">
                  <div>
                    <div className="text-[14px] font-extrabold">Сбросить демо-данные</div>
                    <div className="text-[12px] text-ink-500 font-semibold">Восстановит исходные правила, аптеки и фармацевтов</div>
                  </div>
                  <Button variant="outline">Сбросить</Button>
                </div>
                <div className="card-soft p-4 flex items-center justify-between border-red-200/60 bg-red-50/40">
                  <div>
                    <div className="text-[14px] font-extrabold text-accent-danger">Удалить рабочее пространство</div>
                    <div className="text-[12px] text-ink-500 font-semibold">Действие необратимо — потребуется подтверждение по email</div>
                  </div>
                  <Button variant="danger">Удалить</Button>
                </div>
              </div>
            </SectionCard>
          )}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { SettingsSection });
