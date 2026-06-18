// Тесты ProductGallery — главное фото + миниатюры + бейдж обложки + лайтбокс + empty.

import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProductGallery } from './ProductGallery'

const IMGS = ['https://m/a.jpg', 'https://m/b.jpg', 'https://m/c.jpg']

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
    const main = screen.getByTestId('promo-gallery-main').querySelector('img') as HTMLImageElement
    expect(main.src).toContain('b.jpg')
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
    const main = screen.getByTestId('promo-gallery-main').querySelector('img') as HTMLImageElement
    expect(main.src).toContain('c.jpg')
  })

  it('клик по главному фото открывает лайтбокс', async () => {
    const user = userEvent.setup()
    render(<ProductGallery images={IMGS} effective={IMGS[0]} />)
    expect(screen.queryByTestId('promo-gallery-zoom-img')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('promo-gallery-main'))
    expect(screen.getByTestId('promo-gallery-zoom-img')).toBeInTheDocument()
  })

  it('одно фото → миниатюр нет', () => {
    render(<ProductGallery images={[IMGS[0]]} effective={IMGS[0]} />)
    expect(screen.getByTestId('promo-gallery-main')).toBeInTheDocument()
    expect(screen.queryByTestId('promo-gallery-thumb-0')).not.toBeInTheDocument()
  })
})
