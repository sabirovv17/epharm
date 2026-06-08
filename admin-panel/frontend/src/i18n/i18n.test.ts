// Тесты i18n: translate (fallback), useT через store, setLanguage + персист.

import { describe, expect, it, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { translate } from './index'
import { useT } from './index'
import { dict } from './dict'
import { useUiStore } from '@/app/store'

beforeEach(() => {
  useUiStore.setState({ language: 'ru' })
})

describe('translate', () => {
  it('возвращает значение на нужном языке', () => {
    expect(translate('ru', 'nav.dashboard')).toBe('Дашборд аналитики')
    expect(translate('kk', 'nav.dashboard')).toBe('Аналитика дашборды')
  })

  it('fallback на ru если в kk ключа нет', () => {
    // придумываем отсутствующий в kk ключ — должен вернуть ru
    const ruOnly = '__ru_only_test__'
    ;(dict.ru as Record<string, string>)[ruOnly] = 'Только RU'
    expect(translate('kk', ruOnly)).toBe('Только RU')
    delete (dict.ru as Record<string, string>)[ruOnly]
  })

  it('fallback на сам ключ если нигде нет', () => {
    expect(translate('ru', 'nonexistent.key')).toBe('nonexistent.key')
  })

  it('интерполяция переменных', () => {
    ;(dict.ru as Record<string, string>)['__greet__'] = 'Привет, {name}'
    expect(translate('ru', '__greet__', { name: 'Амир' })).toBe('Привет, Амир')
    delete (dict.ru as Record<string, string>)['__greet__']
  })
})

describe('useT — реагирует на язык в store', () => {
  it('переключение языка меняет результат', () => {
    const { result, rerender } = renderHook(() => useT())
    expect(result.current('nav.settings')).toBe('Настройки')
    useUiStore.getState().setLanguage('kk')
    rerender()
    expect(result.current('nav.settings')).toBe('Баптаулар')
  })
})

describe('store.setLanguage', () => {
  it('обновляет language', () => {
    useUiStore.getState().setLanguage('kk')
    expect(useUiStore.getState().language).toBe('kk')
  })

  it('ru/kk словари покрывают одинаковый набор ключей', () => {
    const ruKeys = Object.keys(dict.ru).sort()
    const kkKeys = Object.keys(dict.kk).sort()
    expect(kkKeys).toEqual(ruKeys)
  })
})
