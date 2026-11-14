import { Routes } from '@angular/router';
import { authenticated } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login').then((m) => m.Login) },
  { path: 'account', loadComponent: () => import('./pages/account').then((m) => m.Account) },
  {
    path: '',
    canActivate: [authenticated],
    loadComponent: () => import('./pages/home').then((m) => m.Home),
  },
  {
    path: 'settings',
    canActivate: [authenticated],
    loadComponent: () => import('./pages/settings').then((m) => m.SettingsPage),
  },
  {
    path: 'payers',
    canActivate: [authenticated],
    loadComponent: () => import('./pages/payers').then((m) => m.PayersPage),
  },
  {
    path: 'claims',
    canActivate: [authenticated],
    loadComponent: () => import('./pages/claims').then((m) => m.ClaimsPage),
  },
  {
    path: 'denials',
    canActivate: [authenticated],
    loadComponent: () => import('./pages/denials').then((m) => m.DenialsPage),
  },
  {
    path: 'members',
    canActivate: [authenticated],
    loadComponent: () => import('./pages/members').then((m) => m.Members),
  },
  {
    path: 'audit',
    canActivate: [authenticated],
    loadComponent: () => import('./pages/audit').then((m) => m.AuditPage),
  },
  { path: '**', redirectTo: '' },
];
