import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { AuthService } from './auth.service';

export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.validateSession().pipe(
    map((ok) => {
      if (!ok) {
        return router.parseUrl('/login');
      }
      if (!auth.isAdmin()) {
        return router.parseUrl('/welcome');
      }
      return true;
    })
  );
};

