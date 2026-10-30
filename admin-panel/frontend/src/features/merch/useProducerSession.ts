import { useSyncExternalStore } from 'react'
import { producerClient } from './producer-client'

export function useProducerSession() {
  return useSyncExternalStore(
    producerClient.subscribe,
    producerClient.snapshot,
    producerClient.snapshot,
  )
}
