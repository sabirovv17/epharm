// AI-Экзаменация — automated training tests.

function AIExamSection() {
  const [tab, setTab] = React.useState('results');
  const toast = useToast();

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="AI-Экзаменация" subtitle="Автоматическая проверка знаний фармацевта по линейке Jadran" actions={<>
        <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Экспорт</Button>
        <Button variant="primary" size="md" leading={<IconPlus size={14}/>}>Новый экзамен</Button>
      </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Прошли в мае" value="312" sub="из 1 248" accent="blue" icon={<IconAIExam size={18}/>}/>
        <Metric label="Сдали" value="248" sub="79% pass rate" accent="green" icon={<IconCheck size={18}/>} delta={+6.4}/>
        <Metric label="Средний балл" value="76" sub="из 100" accent="amber" icon={<IconStar size={18}/>}/>
        <Metric label="Выплачено бонусов" value="198 400 ₸" accent="purple" icon={<IconFinance size={18}/>}/>
      </div>

      <div className="card">
        <div className="px-5 pt-3">
          <Tabs value={tab} onChange={setTab} items={[
            {value:'results', label:'Результаты'},
            {value:'questions', label:'Банк вопросов', count: AD.AI_EXAM_BANK.length},
            {value:'config',  label:'Настройки'},
          ]}/>
        </div>

        {tab === 'results' && (
          <div className="max-h-[600px] overflow-auto scrollbar-thin">
            <table className="tbl">
              <thead><tr><th>Фармацевт</th><th>Аптека</th><th>Попыток</th><th>Балл</th><th>Результат</th><th>Бонус</th><th>Дата</th></tr></thead>
              <tbody>
                {AD.AI_EXAM_RESULTS.map(r => (
                  <tr key={r.id}>
                    <td><div className="flex items-center gap-2"><Avatar name={r.name} size={26}/><span className="font-bold">{r.name}</span></div></td>
                    <td>{r.pharmacy}</td>
                    <td className="num">{r.attempts}</td>
                    <td>
                      <div className="flex items-center gap-2">
                        <span className="text-[14px] font-extrabold num" style={{color: r.score>=70?'#0F8F55':r.score>=50?'#B45309':'#B91C1C'}}>{r.score}</span>
                        <div className="w-16"><ProgressBar value={r.score} max={100} color={r.score>=70?'#16C97A':r.score>=50?'#F4B73A':'#E5484D'}/></div>
                      </div>
                    </td>
                    <td>{r.passed ? <span className="chip chip-green"><IconCheck size={11}/>Сдал</span> : <span className="chip chip-red">Провал</span>}</td>
                    <td className="num font-extrabold">{r.bonus ? AD.fmtKzt(r.bonus) : '—'}</td>
                    <td className="num text-[12px]">{r.takenAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'questions' && (
          <div className="p-5 flex flex-col gap-3">
            {AD.AI_EXAM_BANK.map(q => (
              <div key={q.id} className="card-soft p-4">
                <div className="flex items-start gap-3">
                  <span className="w-8 h-8 rounded-lg bg-ink-100 text-ink-500 flex items-center justify-center flex-none font-extrabold text-[12px]">{q.id.slice(1)}</span>
                  <div className="flex-1">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="chip chip-ink">{q.topic}</span>
                      <span className={`chip ${q.difficulty==='easy'?'chip-green':q.difficulty==='medium'?'chip-amber':'chip-red'}`}>{q.difficulty==='easy'?'Лёгкий':q.difficulty==='medium'?'Средний':'Сложный'}</span>
                      <span className="ml-auto text-[12px] font-bold text-ink-500 num">Проходит {Math.round(q.passed*100)}%</span>
                    </div>
                    <div className="text-[14px] font-extrabold text-ink-900 mb-1.5">{q.q}</div>
                    <div className="text-[13px] text-ink-700 bg-brand-green-50/60 rounded-lg p-2.5 border border-brand-green-200/40">
                      <span className="text-[11px] font-bold text-brand-green-700 uppercase tracking-[0.06em] block mb-1">Эталонный ответ</span>
                      {q.a}
                    </div>
                  </div>
                  <IconButton><IconEdit size={14}/></IconButton>
                </div>
              </div>
            ))}
          </div>
        )}

        {tab === 'config' && (
          <div className="p-5 grid grid-cols-2 gap-4">
            <div className="card-soft p-4">
              <div className="text-[14px] font-extrabold mb-3">Параметры экзамена</div>
              <div className="flex flex-col gap-3">
                <Field label="Проходной балл"><Input defaultValue="70" type="number"/></Field>
                <Field label="Количество вопросов в экзамене"><Input defaultValue="10" type="number"/></Field>
                <Field label="Время на экзамен (мин)"><Input defaultValue="15" type="number"/></Field>
                <Field label="Попыток в месяц"><Input defaultValue="3" type="number"/></Field>
              </div>
            </div>
            <div className="card-soft p-4">
              <div className="text-[14px] font-extrabold mb-3">Бонусы</div>
              <div className="flex flex-col gap-3">
                <Field label="За сдачу" hint="С первого раза"><Input defaultValue="800" type="number"/></Field>
                <Field label="За идеальный балл (100)"><Input defaultValue="1500" type="number"/></Field>
                <Field label="За улучшение балла на 20+"><Input defaultValue="400" type="number"/></Field>
                <Field>
                  <Toggle on={true} onChange={()=>{}} label="Сертификат при сдаче — отправлять в PDF"/>
                </Field>
              </div>
            </div>
            <div className="col-span-2 flex justify-end">
              <Button variant="primary" leading={<IconCheck size={14}/>} onClick={()=>toast.push('Настройки сохранены')}>Сохранить настройки</Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

Object.assign(window, { AIExamSection });
