import { Link } from 'react-router-dom'
import { Logo } from '@/layout/Logo'

export default function MerchPortalPage() {
  return (
    <main className="min-h-screen bg-paper px-4 py-10 sm:py-16">
      <div className="mx-auto max-w-4xl space-y-8">
        <header className="space-y-4">
          <div className="flex items-center gap-3">
            <Logo size={32} />
            <strong>ePharm · Мерчендайзинг</strong>
          </div>
          <h1 className="text-3xl font-bold">Выберите рабочую область</h1>
          <p className="text-ink-500">
            Для каждой области используется отдельный вход и выданные права доступа.
          </p>
        </header>
        <div className="grid gap-4 md:grid-cols-3">
          <section className="card flex flex-col gap-4 p-6">
            <h2 className="text-xl font-bold">Производитель</h2>
            <p className="flex-1 text-sm text-ink-500">
              Планы размещений, документы и отчёты вашей компании.
            </p>
            <Link className="btn btn-md btn-primary" to="/producer/login">
              Кабинет производителя
            </Link>
          </section>
          <section className="card flex flex-col gap-4 p-6">
            <h2 className="text-xl font-bold">Фармацевт</h2>
            <p className="flex-1 text-sm text-ink-500">
              Личные задания и обучение. Конкретное задание также можно открыть по QR-коду.
            </p>
            <a className="btn btn-md btn-primary" href="https://epharm.inkar.kz/learn">
              Личный кабинет фармацевта
            </a>
          </section>
          <section className="card flex flex-col gap-4 p-6">
            <h2 className="text-xl font-bold">HQ Inkar</h2>
            <p className="flex-1 text-sm text-ink-500">
              Управление сетью, обучением и мерчендайзингом в административной панели ePharm.
            </p>
            <a className="btn btn-md btn-outline" href="https://epharm.inkar.kz/login">
              Вход HQ
            </a>
          </section>
        </div>
      </div>
    </main>
  )
}
