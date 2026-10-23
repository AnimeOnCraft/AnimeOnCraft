import {vpnHint} from './connection-hint.js';
import { useEffect, useState } from 'react';
import type { PublicApi, PublicRequest } from './public-api.js';
import { PublicApiError } from './public-api.js';
export type ResourceState<T> = { status: 'loading' } | { status: 'ready'; data: T } | { status: 'error'; message: string };
export function useResource<T>(api: PublicApi, request: PublicRequest, parse: (value: unknown) => T, revision = 0): ResourceState<T> {
  const [state, setState] = useState<ResourceState<T>>({ status: 'loading' });
  const { resource, key, cursor } = request;
  useEffect(() => {
    const controller = new AbortController(); setState({ status: 'loading' });
    const timer = setTimeout(() => controller.abort(), 6000);
    void api.read({ resource, ...(key === undefined ? {} : { key }), ...(cursor === undefined ? {} : { cursor }) }, parse, controller.signal)
      .then(data => { if (!controller.signal.aborted) setState({ status: 'ready', data }); })
      .catch(error => { if (!disposed) setState({ status: 'error', message: error instanceof PublicApiError && error.code === 'NOT_FOUND' ? 'Запись больше не доступна.' : error instanceof PublicApiError && error.code === 'INVALID_DATA' ? 'Получен неподдерживаемый ответ сервиса.' : `Сервис не отвечает. ${vpnHint}` }); })
      .finally(() => clearTimeout(timer));
    let disposed = false;
    return () => { disposed = true; clearTimeout(timer); controller.abort(); };
  }, [api, resource, key, cursor, parse, revision]);
  return state;
}
