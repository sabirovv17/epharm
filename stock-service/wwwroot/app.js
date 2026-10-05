(() => {
  'use strict'

  const PAGE_SIZE = 100
  const collator = new Intl.Collator('ru', { sensitivity: 'base', numeric: true })
  const countFormat = new Intl.NumberFormat('ru-RU')
  const quantityFormat = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 8 })
  const priceFormat = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const nodes = {
    breadcrumbs: document.getElementById('breadcrumbs'),
    eyebrow: document.getElementById('eyebrow'),
    title: document.getElementById('page-title'),
    subtitle: document.getElementById('page-subtitle'),
    count: document.getElementById('overview-count'),
    notice: document.getElementById('notice'),
    toolbar: document.getElementById('toolbar-copy'),
    searchForm: document.getElementById('search-form'),
    searchInput: document.getElementById('search-input'),
    excel: document.getElementById('excel-link'),
    content: document.getElementById('content'),
    pagination: document.getElementById('pagination'),
    refresh: document.getElementById('refresh-button'),
  }

  let citiesCache = null
  const pharmacyCache = new Map()
  let activeRequest = null

  function node(tag, className, value) {
    const result = document.createElement(tag)
    if (className) result.className = className
    if (value !== undefined && value !== null) result.textContent = String(value)
    return result
  }

  function textOrDash(value) {
    const text = String(value ?? '').trim()
    return text || '—'
  }

  function cityLabel(value) {
    const text = String(value ?? '').trim()
    return !text || text === '—' ? 'Город не указан' : text
  }

  function number(value, formatter = countFormat) {
    if (value === null || value === undefined || value === '') return '—'
    const parsed = Number(value)
    return Number.isFinite(parsed) ? formatter.format(parsed) : '—'
  }

  function dateTime(value) {
    if (!value) return 'нет данных'
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return 'нет данных'
    return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
  }

  function expiryDate(value) {
    if (!value) return '—'
    const match = String(value).match(/^(\d{4})-(\d{2})-(\d{2})/)
    return match ? `${match[3]}.${match[2]}.${match[1]}` : textOrDash(value)
  }

  function pharmacyWord(value) {
    const n = Math.abs(Number(value)) % 100
    const digit = n % 10
    return n > 10 && n < 20 || digit >= 5 || digit === 0 ? 'аптек' : digit === 1 ? 'аптека' : 'аптеки'
  }

  function route() {
    const params = new URLSearchParams(window.location.search)
    return {
      city: params.get('city') || '',
      pharmacy: params.get('pharmacy') || '',
      q: params.get('q') || '',
      page: Math.max(1, Math.min(100000, Number.parseInt(params.get('page') || '1', 10) || 1)),
    }
  }

  function navigate(next, replace = false) {
    const params = new URLSearchParams()
    if (next.city) params.set('city', next.city)
    if (next.pharmacy) params.set('pharmacy', next.pharmacy)
    if (next.q) params.set('q', next.q)
    if (next.page > 1) params.set('page', String(next.page))
    const url = `${window.location.pathname}${params.size ? `?${params}` : ''}`
    window.history[replace ? 'replaceState' : 'pushState']({}, '', url)
    render()
    window.scrollTo({ top: 0, behavior: 'auto' })
    nodes.title.focus({ preventScroll: true })
  }

  function apiUrl(path) {
    return new URL(`api/v1/${path}`, document.baseURI)
  }

  async function getJson(path, signal) {
    const response = await fetch(apiUrl(path), { headers: { Accept: 'application/json' }, signal })
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`)
      error.status = response.status
      throw error
    }
    return response.json()
  }

  function setNotice(message, kind = 'warning', onRetry = null) {
    nodes.notice.replaceChildren()
    nodes.notice.hidden = !message
    nodes.notice.className = `notice ${kind}`
    if (!message) return
    nodes.notice.append(node('span', '', message))
    if (onRetry) {
      const retry = node('button', '', 'Повторить')
      retry.type = 'button'
      retry.addEventListener('click', onRetry)
      nodes.notice.append(retry)
    }
  }

  function setToolbar(title, subtitle = '') {
    nodes.toolbar.replaceChildren(node('strong', '', title), node('span', '', subtitle))
  }

  function setSearch(placeholder, value) {
    nodes.searchForm.hidden = false
    nodes.searchInput.placeholder = placeholder
    nodes.searchInput.value = value
    nodes.searchInput.setAttribute('aria-label', placeholder)
  }

  function addCrumb(label, next) {
    const li = node('li')
    if (next) {
      const button = node('button', '', label)
      button.type = 'button'
      button.addEventListener('click', () => navigate(next))
      li.append(button)
    } else {
      li.textContent = label
      li.setAttribute('aria-current', 'page')
    }
    nodes.breadcrumbs.append(li)
  }

  function setChrome(current) {
    nodes.breadcrumbs.replaceChildren()
    nodes.count.hidden = true
    nodes.excel.hidden = true
    nodes.searchForm.hidden = false
    nodes.pagination.hidden = true
    if (current.pharmacy) {
      addCrumb('Города', {})
      if (current.city) addCrumb(cityLabel(current.city), { city: current.city })
      addCrumb('Аптека', null)
      nodes.eyebrow.textContent = 'Остатки аптеки'
      nodes.title.textContent = 'Загрузка аптеки…'
      nodes.subtitle.textContent = 'Остатки товаров по последнему доступному снимку.'
      setToolbar('Товары в наличии', 'Поиск выполняется по всем товарам аптеки')
      setSearch('Поиск по товару или штрихкоду', current.q)
    } else if (current.city) {
      addCrumb('Города', {})
      addCrumb(cityLabel(current.city), null)
      nodes.eyebrow.textContent = 'Аптеки города'
      nodes.title.textContent = cityLabel(current.city)
      nodes.subtitle.textContent = 'Выберите аптеку, чтобы посмотреть остатки и скачать Excel.'
      setToolbar('Список аптек', 'Поиск по названию или адресу')
      setSearch('Найти аптеку', current.q)
    } else {
      addCrumb('Города', null)
      nodes.eyebrow.textContent = 'Обзор сети'
      nodes.title.textContent = 'Остатки по городам'
      nodes.subtitle.textContent = 'Выберите город, затем аптеку, чтобы посмотреть доступный снимок остатков.'
      setToolbar('Города', 'Число аптек по каждому городу')
      setSearch('Найти город', current.q)
    }
  }

  function loading() {
    const skeleton = node('div', 'skeleton')
    for (let i = 0; i < 4; i++) skeleton.append(node('div', 'skeleton-line'))
    nodes.content.replaceChildren(skeleton)
    nodes.content.setAttribute('aria-busy', 'true')
  }

  function empty(title, description, action) {
    const wrapper = node('div', 'empty-state')
    wrapper.append(node('div', 'empty-icon', '⌕'), node('strong', '', title), node('p', '', description))
    if (action) wrapper.append(action)
    nodes.content.replaceChildren(wrapper)
    nodes.content.setAttribute('aria-busy', 'false')
  }

  function retryButton() {
    const button = node('button', 'button button-plain', 'Повторить загрузку')
    button.type = 'button'
    button.addEventListener('click', render)
    return button
  }

  function failed(error) {
    const missing = error.status === 404
    empty(
      missing ? 'Данные не найдены' : 'Не удалось загрузить данные',
      missing ? 'Проверьте ссылку или вернитесь к списку аптек.' : 'Проверьте подключение и повторите попытку.',
      missing ? null : retryButton(),
    )
    setNotice(missing ? 'Запрошенная аптека или город не найдены.' : 'Сервис временно не отвечает. Данные могут быть недоступны.', 'error')
  }

  async function loadCities(signal, force) {
    if (citiesCache && !force) return citiesCache
    const result = await getJson('cities', signal)
    if (!Array.isArray(result)) throw new Error('Invalid cities response')
    citiesCache = result
    return result
  }

  function showCities(cities, current) {
    const allCount = cities.reduce((sum, item) => sum + (Number(item.count) || 0), 0)
    nodes.count.hidden = false
    nodes.count.replaceChildren(node('strong', '', number(allCount)), node('span', '', `${pharmacyWord(allCount)} в ${number(cities.length)} городах`))
    const filtered = cities
      .filter((item) => String(item.city ?? '').toLocaleLowerCase('ru').includes(current.q.toLocaleLowerCase('ru')))
      .sort((a, b) => collator.compare(String(a.city ?? ''), String(b.city ?? '')))
    setToolbar('Города', `${number(cities.length)} городов · ${number(allCount)} ${pharmacyWord(allCount)}`)
    if (!filtered.length) {
      empty(current.q ? 'Город не найден' : 'Города пока не загружены', current.q ? 'Попробуйте изменить поисковый запрос.' : 'Список появится после получения данных об аптеках.')
      return
    }
    const grid = node('div', 'city-grid')
    for (const city of filtered) {
      const name = cityLabel(city.city)
      const card = node('button', 'city-card')
      card.type = 'button'
      card.setAttribute('aria-label', `${name}, ${number(city.count)} ${pharmacyWord(city.count)}`)
      const top = node('span', 'city-card-top')
      top.append(node('span', 'city-icon', '⌂'), node('span', 'city-arrow', '→'))
      const bottom = node('span')
      bottom.append(node('span', 'city-name', name), node('span', 'city-count', `${number(city.count)} ${pharmacyWord(city.count)}`))
      card.append(top, bottom)
      card.addEventListener('click', () => navigate({ city: String(city.city ?? '') }))
      grid.append(card)
    }
    nodes.content.replaceChildren(grid)
    nodes.content.setAttribute('aria-busy', 'false')
  }

  async function loadPharmacies(city, signal, force) {
    if (pharmacyCache.has(city) && !force) return pharmacyCache.get(city)
    const url = apiUrl('pharmacies')
    url.searchParams.set('city', city)
    const response = await fetch(url, { headers: { Accept: 'application/json' }, signal })
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`)
      error.status = response.status
      throw error
    }
    const result = await response.json()
    if (!Array.isArray(result)) throw new Error('Invalid pharmacies response')
    pharmacyCache.set(city, result)
    return result
  }

  function statusMeta(value) {
    switch (String(value ?? '').toLowerCase()) {
      case 'fresh': return ['Актуально', 'fresh']
      case 'stale': return ['Устарело', 'stale']
      case 'error': return ['Ошибка обновления', 'error']
      default: return ['Ожидает данных', 'pending']
    }
  }

  function statusBadge(value) {
    const [label, kind] = statusMeta(value)
    return node('span', `status ${kind}`, label)
  }

  function showPharmacies(pharmacies, current) {
    const filtered = pharmacies
      .filter((p) => `${p.name ?? ''} ${p.address ?? ''} ${p.id ?? ''}`.toLocaleLowerCase('ru').includes(current.q.toLocaleLowerCase('ru')))
      .sort((a, b) => collator.compare(String(a.name ?? ''), String(b.name ?? '')))
    setToolbar('Список аптек', `${number(filtered.length)} ${pharmacyWord(filtered.length)}${current.q ? ' найдено' : ''}`)
    if (!filtered.length) {
      empty(current.q ? 'Аптека не найдена' : 'В этом городе пока нет аптек', current.q ? 'Попробуйте изменить поисковый запрос.' : 'Список появится после подключения аптек к сервису.')
      return
    }
    const list = node('ul', 'list')
    for (const pharmacy of filtered) {
      const li = node('li')
      const button = node('button', 'pharmacy-link')
      button.type = 'button'
      const info = node('span', 'pharmacy-info')
      const label = node('span')
      label.append(node('strong', '', textOrDash(pharmacy.name)), node('small', '', textOrDash(pharmacy.address)))
      info.append(node('span', 'pharmacy-avatar', '+'), label)
      const meta = node('span', 'pharmacy-meta')
      meta.append(statusBadge(pharmacy.status), node('span', 'pharmacy-qty', pharmacy.stockCount === null || pharmacy.stockCount === undefined ? 'Нет снимка' : `${number(pharmacy.stockCount)} позиций`), node('span', 'pharmacy-arrow', '→'))
      button.append(info, meta)
      button.addEventListener('click', () => navigate({ city: current.city, pharmacy: String(pharmacy.id ?? '') }))
      li.append(button)
      list.append(li)
    }
    nodes.content.replaceChildren(list)
    nodes.content.setAttribute('aria-busy', 'false')
  }

  function stockRow(item) {
    const row = node('tr')
    const product = node('td', 'product', textOrDash(item.name))
    if (item.partId !== undefined && item.partId !== null) product.append(node('small', '', `ID ${item.partId}`))
    row.append(product)
    row.append(node('td', 'mono', textOrDash(item.barcode || item.manufacturerBarcode)))
    row.append(node('td', 'mono', textOrDash(item.series)))
    row.append(node('td', 'mono', expiryDate(item.expiryDate)))
    const qty = node('td', 'qty', number(item.quantity, quantityFormat))
    if (item.unit) qty.append(node('span', '', ` ${item.unit}`))
    row.append(qty)
    row.append(node('td', 'price', item.price === null || item.price === undefined ? '—' : `${number(item.price, priceFormat)} ₸`))
    return row
  }

  function showPagination(total, offset, limit, current) {
    nodes.pagination.replaceChildren()
    if (total <= limit) {
      nodes.pagination.hidden = true
      return
    }
    nodes.pagination.hidden = false
    const start = offset + 1
    const end = Math.min(total, offset + limit)
    nodes.pagination.append(node('span', '', `${number(start)}–${number(end)} из ${number(total)}`))
    const actions = node('div', 'pagination-actions')
    const previous = node('button', 'button button-plain', 'Назад')
    previous.type = 'button'
    previous.disabled = offset === 0
    previous.addEventListener('click', () => navigate({ ...current, page: current.page - 1 }))
    const next = node('button', 'button button-plain', 'Далее')
    next.type = 'button'
    next.disabled = end >= total
    next.addEventListener('click', () => navigate({ ...current, page: current.page + 1 }))
    actions.append(previous, next)
    nodes.pagination.append(actions)
  }

  function showStocks(result, current) {
    if (!result || !Array.isArray(result.items) || !result.pharmacy) throw new Error('Invalid stocks response')
    const pharmacy = result.pharmacy
    const cached = pharmacyCache.get(pharmacy.city)
    if (cached) {
      const index = cached.findIndex((item) => String(item.id) === String(pharmacy.id))
      if (index >= 0) cached[index] = pharmacy
    }
    const total = Number(result.total) || 0
    const limit = Number(result.limit) || PAGE_SIZE
    const offset = Number(result.offset) || 0
    const asOf = result.asOf || pharmacy.lastUpdatedAt
    const status = String(pharmacy.status || 'pending').toLowerCase()
    nodes.title.textContent = textOrDash(pharmacy.name)
    nodes.subtitle.textContent = `${cityLabel(pharmacy.city)} · ${textOrDash(pharmacy.address)}`
    nodes.breadcrumbs.lastElementChild.textContent = textOrDash(pharmacy.name)
    setToolbar('Остатки товаров', `${number(total)} товарных позиций`)
    const excelUrl = apiUrl(`pharmacies/${encodeURIComponent(current.pharmacy)}/stocks.xlsx`)
    nodes.excel.href = excelUrl.href
    nodes.excel.hidden = false

    if (status === 'stale') setNotice('Показан устаревший снимок. Новые данные пока не поступили.')
    else if (status === 'error') setNotice(asOf
      ? 'Последнее обновление завершилось ошибкой. Показан последний доступный снимок.'
      : 'Не удалось получить первый снимок остатков. Повторная попытка будет выполнена автоматически.', 'error')
    else if (status === 'pending') setNotice('Первый снимок остатков ещё не получен.')
    else setNotice('')

    const summary = node('div', 'detail-summary')
    summary.append(statusBadge(status), node('span', '', `Обновлено: ${dateTime(asOf)}`), node('span', '', `${number(total)} позиций`))
    const wrapper = node('div')
    wrapper.append(summary)
    if (!result.items.length) {
      const noRows = node('div', 'empty-state')
      noRows.append(
        node('div', 'empty-icon', '⌕'),
        node('strong', '', current.q ? 'Товары не найдены' : status === 'pending' ? 'Ожидаем первый снимок' : 'Остатков пока нет'),
        node('p', '', current.q ? 'Измените поисковый запрос и повторите поиск.' : 'Данные появятся после получения нового снимка от аптеки.'),
      )
      wrapper.append(noRows)
    } else {
      const scroll = node('div', 'table-scroll')
      const table = node('table', 'stock-table')
      const caption = node('caption', 'visually-hidden', `Остатки товаров аптеки ${textOrDash(pharmacy.name)}`)
      const head = node('thead')
      const labels = ['Товар', 'Штрихкод', 'Партия', 'Годен до', 'Остаток', 'Цена']
      const headingRow = node('tr')
      for (const label of labels) {
        const th = node('th', '', label)
        th.scope = 'col'
        headingRow.append(th)
      }
      head.append(headingRow)
      const body = node('tbody')
      for (const item of result.items) body.append(stockRow(item))
      table.append(caption, head, body)
      scroll.append(table)
      wrapper.append(scroll)
    }
    nodes.content.replaceChildren(wrapper)
    nodes.content.setAttribute('aria-busy', 'false')
    showPagination(total, offset, limit, current)
  }

  async function loadStocks(current, signal) {
    const url = apiUrl(`pharmacies/${encodeURIComponent(current.pharmacy)}/stocks`)
    url.searchParams.set('limit', String(PAGE_SIZE))
    url.searchParams.set('offset', String((current.page - 1) * PAGE_SIZE))
    if (current.q) url.searchParams.set('q', current.q)
    const response = await fetch(url, { headers: { Accept: 'application/json' }, signal })
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`)
      error.status = response.status
      throw error
    }
    return response.json()
  }

  async function render(force = false) {
    if (activeRequest) activeRequest.abort()
    activeRequest = new AbortController()
    const signal = activeRequest.signal
    const current = route()
    setChrome(current)
    setNotice('')
    loading()
    try {
      if (current.pharmacy) {
        const data = await loadStocks(current, signal)
        if (!signal.aborted) showStocks(data, current)
      } else if (current.city) {
        const data = await loadPharmacies(current.city, signal, force)
        if (!signal.aborted) showPharmacies(data, current)
      } else {
        const data = await loadCities(signal, force)
        if (!signal.aborted) showCities(data, current)
      }
    } catch (error) {
      if (!signal.aborted) failed(error)
    }
  }

  nodes.searchForm.addEventListener('submit', (event) => {
    event.preventDefault()
    navigate({ ...route(), q: nodes.searchInput.value.trim(), page: 1 })
  })
  nodes.refresh.addEventListener('click', () => {
    citiesCache = null
    pharmacyCache.clear()
    render(true)
  })
  window.addEventListener('popstate', () => render())
  render()
})()
