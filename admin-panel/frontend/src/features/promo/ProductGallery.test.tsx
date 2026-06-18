// Тесты ProductGallery — листаемая карусель: главное фото + стрелки + счётчик +
// миниатюры + бейдж обложки + лайтбокс + устойчивость к битым ссылкам + empty.

import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProductGallery } from './ProductGallery'

const IMGS = ['https://m/a.jpg', 'https://m/b.jpg', 'https://m/c.jpg']

const mainSrc = () =>
  (screen.getByTestId('promo-gallery-main').querySelector('img') as HTMLImageElement).src

describe('ProductGallery', () => {
  it('пустой список → empty state', () => {
    render(<ProductGallery images={[]} effective={null} />)
    expect(screen.getByTestId('promo-gallery-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('promo-gallery')).not.toBeInTheDocument()
  })

  it('рендерит главное фото и миниатюры по числу изображений', () => {
    render(<ProductGallery images={IMGS} effective={IMGS[0]} />)
    expect(screen.getByTestId('promo-gallery-main')).toBeInTheDocument()
    expect(screen.getByTestId('promo-gallery-thumb-0')).toBeInTheDocument()
    expect(screen.getByTestId('promo-gallery-thumb-2')).toBeInTheDocument()
  })

  it('главное фото по умолчанию — текущая обложка (effective)', () => {
    render(<ProductGallery images={IMGS} effective={IMGS[1]} />)
    expect(mainSrc()).toContain('b.jpg')
  })

  it('бейдж «обложка» только на effective-миниатюре', () => {
    render(<ProductGallery images={IMGS} effective={IMGS[1]} />)
    const badged = screen.getByTestId('promo-gallery-thumb-1')
    expect(badged).toHaveTextContent(/Обложка/i)
    expect(screen.getByTestId('promo-gallery-thumb-0')).not.toHaveTextContent(/Обложка/i)
  })

  it('клик по миниатюре меняет главное фото', async () => {
    const user = userEvent.setup()
    render(<ProductGallery images={IMGS} effective={IMGS[0]} />)
    await user.click(screen.getByTestId('promo-gallery-thumb-2'))
    expect(mainSrc()).toContain('c.jpg')
  })

  it('стрелки листают вперёд/назад с зацикливанием + счётчик', async () => {
    const user = userEvent.setup()
    render(<ProductGallery images={IMGS} effective={IMGS[0]} />)
    expect(screen.getByTestId('promo-gallery-counter')).toHaveTextContent('1 / 3')

    await user.click(screen.getByTestId('promo-gallery-next'))
    expect(mainSrc()).toContain('b.jpg')
    expect(screen.getByTestId('promo-gallery-counter')).toHaveTextContent('2 / 3')

    await user.click(screen.getByTestId('promo-gallery-prev'))
    expect(mainSrc()).toContain('a.jpg')

    // Назад с первого кадра → зацикливание на последний.
    await user.click(screen.getByTestId('promo-gallery-prev'))
    expect(mainSrc()).toContain('c.jpg')
    expect(screen.getByTestId('promo-gallery-counter')).toHaveTextContent('3 / 3')
  })

  it('битое фото (onError) тихо выпадает из галереи', () => {
    render(<ProductGallery images={IMGS} effective={IMGS[0]} />)
    expect(screen.getByTestId('promo-gallery-counter')).toHaveTextContent('1 / 3')

    // Ломаем третий кадр (c.jpg) — должен исчезнуть, счётчик пересчитаться.
    const brokenImg = screen
      .getByTestId('promo-gallery-thumb-2')
      .querySelector('img') as HTMLImageElement
    fireEvent.error(brokenImg)

    expect(screen.getByTestId('promo-gallery-counter')).toHaveTextContent('1 / 2')
    expect(screen.queryByTestId('promo-gallery-thumb-2')).not.toBeInTheDocument()
  })

  it('все ссылки битые → состояние «фото не загрузились», без broken-иконок', () => {
    render(<ProductGallery images={IMGS} effective={IMGS[0]} />)
    // Поочерёдно ломаем текущее главное фото, пока галерея не опустеет.
    for (let i = 0; i < 5 && screen.queryByTestId('promo-gallery'); i++) {
      const img = document.querySelector(
        '[data-testid="promo-gallery-main"] img',
      ) as HTMLImageElement | null
      if (img) fireEvent.error(img)
    }
    expect(screen.queryByTestId('promo-gallery')).not.toBeInTheDocument()
    expect(screen.getByTestId('promo-gallery-empty')).toHaveTextContent(/не загрузились/i)
  })

  it('клик по главному фото открывает лайтбокс', async () => {
    const user = userEvent.setup()
    render(<ProductGallery images={IMGS} effective={IMGS[0]} />)
    expect(screen.queryByTestId('promo-gallery-zoom-img')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('promo-gallery-main'))
    expect(screen.getByTestId('promo-gallery-zoom-img')).toBeInTheDocument()
  })

  it('одно фото → ни миниатюр, ни стрелок, ни счётчика', () => {
    render(<ProductGallery images={[IMGS[0]]} effective={IMGS[0]} />)
    expect(screen.getByTestId('promo-gallery-main')).toBeInTheDocument()
    expect(screen.queryByTestId('promo-gallery-thumb-0')).not.toBeInTheDocument()
    expect(screen.queryByTestId('promo-gallery-prev')).not.toBeInTheDocument()
    expect(screen.queryByTestId('promo-gallery-counter')).not.toBeInTheDocument()
  })
})
