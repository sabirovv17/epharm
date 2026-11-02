import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { useUiStore } from '@/app/store'
import type { ProductDto } from '@/lib/api-types'
import { ProductBlock } from './ProductBlock'

const product: ProductDto = {
  id: 'scan',
  name: 'Триггерный товар',
  brand: 'Производитель',
  vendor: '',
  mnn: '',
  price: 100,
}

beforeEach(() => useUiStore.setState({ language: 'ru' }))

describe('ProductBlock compound conditions', () => {
  it('keeps the primary scope and shows every narrowing factor', () => {
    render(
      <ProductBlock
        product={null}
        fallback={{
          kind: 'acc_group',
          value: 'g',
          label: 'Группа',
          conditions: [
            { kind: 'acc_mnn', value: 'm', label: 'Вещество' },
            { kind: 'acc_subgroup', value: 's', label: 'Подгруппа' },
          ],
        }}
      />,
    )
    expect(screen.getByText('Группа ACC: Группа')).toBeInTheDocument()
    expect(screen.getByText('∩ МНН ACC: Вещество ∩ Подгруппа ACC: Подгруппа')).toBeInTheDocument()
  })

  it('preserves known product presentation and adds its exact conditions', () => {
    const { container } = render(
      <ProductBlock
        product={product}
        fallback={{
          kind: 'product',
          value: product.id,
          conditions: [{ kind: 'acc_mnn', value: 'm', label: 'Вещество' }],
        }}
      />,
    )
    expect(screen.getByText(product.name)).toBeInTheDocument()
    expect(screen.getByText(product.brand)).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeInTheDocument()
    expect(screen.getByText('∩ МНН ACC: Вещество')).toBeInTheDocument()
  })

  it('uses the stable key when the condition has no snapshot label', () => {
    render(
      <ProductBlock
        product={product}
        fallback={{
          kind: 'product',
          value: product.id,
          conditions: [{ kind: 'acc_mnn', value: 'm-key' }],
        }}
      />,
    )
    expect(screen.getByText('∩ МНН ACC: m-key')).toBeInTheDocument()
  })

  it('leaves legacy simple product presentation unchanged', () => {
    render(<ProductBlock product={product} fallback={{ kind: 'product', value: product.id }} />)
    expect(screen.getByText(product.name)).toBeInTheDocument()
    expect(screen.getByText(product.brand)).toBeInTheDocument()
    expect(screen.queryByText(/∩/)).not.toBeInTheDocument()
  })
})
