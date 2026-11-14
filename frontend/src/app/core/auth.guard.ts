import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { Api, errorKey } from './api.service';

export const authenticated: CanActivateFn = () => {
  const api = inject(Api);
  const router = inject(Router);
  return api.refresh().pipe(
    map((user) => (user ? true : router.createUrlTree(['/login']))),
    catchError((error: unknown) =>
      of(router.createUrlTree(['/login'], { queryParams: { failure: errorKey(error) } })),
    ),
  );
};
