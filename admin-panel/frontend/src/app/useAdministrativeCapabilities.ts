import { useQuery } from '@tanstack/react-query'
import { accessApi } from '@/features/settings/access-api'
import { useUiStore } from './store'

/** Fresh server capability, never derived from a role title or persisted offline. */
export function useAdministrativeCapabilities() {
  const user = useUiStore((state) => state.authedUser)
  const query = useQuery({
    queryKey: ['admin-access', 'me', user?.id],
    queryFn: ({ signal }) => accessApi.me(signal),
    enabled: !!user,
    staleTime: 0,
    placeholderData: undefined,
    retry: false,
  })
  return {
    ...query,
    canViewMerchInventory:
      query.isSuccess &&
      query.isFetchedAfterMount &&
      !query.isError &&
      query.data?.canViewMerchInventory === true,
    canViewMerchPlacements:
      query.isSuccess &&
      query.isFetchedAfterMount &&
      !query.isError &&
      query.data?.canViewMerchPlacements === true,
    canViewMerchTasks:
      query.isSuccess &&
      query.isFetchedAfterMount &&
      !query.isError &&
      query.data?.canViewMerchTasks === true,
  }
}
