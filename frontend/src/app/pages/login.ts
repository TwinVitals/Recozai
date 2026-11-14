import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { finalize, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Api, errorKey } from '../core/api.service';
import { Language } from '../core/language.service';
import { en } from '../core/catalog';

@Component({
  imports: [ReactiveFormsModule, RouterLink, TranslatePipe],
  template: `
    <main class="login-page">
      <img class="login-care-image" src="/login-care.jpg" alt="" aria-hidden="true" />

      <section class="login-visual">
        <div class="login-hero-header">
          <div class="login-brand">
            <svg class="login-brand-mark" viewBox="0 0 64 64" fill="none" aria-hidden="true">
              <defs>
                <linearGradient id="recozai-r-top" x1="10" y1="8" x2="54" y2="38" gradientUnits="userSpaceOnUse">
                  <stop offset="0%" stop-color="#36b6ff" />
                  <stop offset="55%" stop-color="#1971eb" />
                  <stop offset="100%" stop-color="#124496" />
                </linearGradient>
                <linearGradient id="recozai-r-leg" x1="12" y1="24" x2="56" y2="56" gradientUnits="userSpaceOnUse">
                  <stop offset="0%" stop-color="#0f3e8a" />
                  <stop offset="45%" stop-color="#1e80f0" />
                  <stop offset="100%" stop-color="#48c6ff" />
                </linearGradient>
              </defs>
              <path
                d="M11 49L28 13H43.5C51.5 13 57 18.2 57 25.5C57 32.8 51.4 38 43.2 38H30L23.5 49H11Z"
                fill="url(#recozai-r-top)"
              />
              <path
                d="M27.5 23.5H41C44.5 23.5 46.8 25.3 46.8 28.2C46.8 31.1 44.5 32.8 41 32.8H32L47 49H59L43.8 34.2L27.5 23.5Z"
                fill="url(#recozai-r-leg)"
              />
              <path
                d="M19 37.5L26.5 24.5L34.5 32.8L23.5 49H11L19 37.5Z"
                fill="url(#recozai-r-leg)"
                opacity="0.9"
              />
            </svg>
            <span class="login-brand-name">{{ 'title' | translate }}</span>
          </div>
          <h2>{{ 'loginProduct' | translate }}</h2>
          <p>{{ 'loginTagline' | translate }}</p>
        </div>

        <p class="login-location">
          <svg class="login-flag" viewBox="0 0 30 20" aria-hidden="true">
            <rect width="30" height="20" rx="3" fill="#fdef42" />
            <path d="M0 3A3 3 0 0 1 3 0H10V20H3A3 3 0 0 1 0 17V3Z" fill="#00853f" />
            <path d="M20 0H27A3 3 0 0 1 30 3V17A3 3 0 0 1 27 20H20V0Z" fill="#e31b23" />
            <polygon
              points="15,6.2 16,9.1 19,9.1 16.6,10.9 17.5,13.8 15,12 12.5,13.8 13.4,10.9 11,9.1 14,9.1"
              fill="#00853f"
            />
          </svg>
          <strong>{{ 'loginCountry' | translate }}</strong>
          <span class="login-dot" aria-hidden="true">•</span>
          <span>{{ 'loginFooter' | translate }}</span>
        </p>
      </section>

      <section class="login-panel">
        <div class="login-card">
          <div class="login-languages" role="group" [attr.aria-label]="'language' | translate">
            <button
              type="button"
              [class.active]="language.translate.getCurrentLang() === 'fr-SN'"
              [attr.aria-pressed]="language.translate.getCurrentLang() === 'fr-SN'"
              (click)="setLanguage('fr-SN')"
            >
              FR
            </button>
            <button
              type="button"
              [class.active]="language.translate.getCurrentLang() === 'en'"
              [attr.aria-pressed]="language.translate.getCurrentLang() === 'en'"
              (click)="setLanguage('en')"
            >
              EN
            </button>
          </div>

          <header class="login-heading">
            <h1>{{ 'loginWelcome' | translate }}</h1>
            <p>{{ 'loginSubtitle' | translate }}</p>
          </header>

          <form class="login-form" [formGroup]="form" (ngSubmit)="submit()">
            <label class="login-field">
              <svg class="login-field-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle cx="12" cy="8" r="4" />
                <path d="M5 20c0-3.6 3.1-6 7-6s7 2.4 7 6" />
              </svg>
              <span class="login-sr-only">{{ 'loginName' | translate }}</span>
              <input
                formControlName="login"
                autocomplete="username"
                maxlength="120"
                [placeholder]="'loginPlaceholder' | translate"
              />
            </label>

            <div class="login-field">
              <svg class="login-field-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect x="5" y="11" width="14" height="10" rx="2" />
                <path d="M8 11V7a4 4 0 0 1 8 0v4" />
              </svg>
              <label class="login-sr-only" for="login-password">
                {{ 'password' | translate }}
              </label>
              <input
                id="login-password"
                formControlName="password"
                [type]="passwordVisible() ? 'text' : 'password'"
                autocomplete="current-password"
                maxlength="72"
                [placeholder]="'password' | translate"
              />
              <button
                class="login-password-toggle"
                type="button"
                [attr.aria-label]="(passwordVisible() ? 'hidePassword' : 'showPassword') | translate"
                [attr.aria-pressed]="passwordVisible()"
                (click)="passwordVisible.update((visible) => !visible)"
              >
                @if (passwordVisible()) {
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M3 3l18 18M10.6 10.6a2 2 0 0 0 2.8 2.8" />
                    <path
                      d="M9.9 5.2A10.7 10.7 0 0 1 12 5c5.5 0 9 7 9 7a14 14 0 0 1-3.1 3.8M6.2 6.2C3.9 7.7 3 12 3 12s3.5 7 9 7a9 9 0 0 0 2.1-.3"
                    />
                  </svg>
                } @else {
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12Z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                }
              </button>
            </div>

            <div class="login-options-row">
              <label class="login-remember">
                <input type="checkbox" formControlName="remember" />
                <span>{{ 'loginRememberMe' | translate }}</span>
              </label>
              <a class="login-forgot-link" routerLink="/account">
                {{ 'loginForgotPassword' | translate }}
              </a>
            </div>

            <button class="login-submit" type="submit" [disabled]="busy()">
              <span>{{ (busy() ? 'loading' : 'login') | translate }}</span>
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M5 12h14M13 6l6 6-6 6" />
              </svg>
            </button>
          </form>

          @if (error()) {
            <p class="login-error" role="alert">{{ error() | translate }}</p>
          }

          <div class="login-divider" aria-hidden="true">
            <span>{{ 'loginOrDivider' | translate }}</span>
          </div>

          <button class="login-sso-button" type="button" (click)="showSsoNotice()">
            <svg class="login-sso-icon" viewBox="0 0 24 24" aria-hidden="true">
              <path
                d="M12 2.5 4.5 5.8v5.6c0 5.2 3.2 9.8 7.5 11.1 4.3-1.3 7.5-5.9 7.5-11.1V5.8L12 2.5Z"
                fill="#1d3b6c"
              />
              <polygon
                points="12,7.3 13.1,10.1 16.1,10.3 13.8,12.2 14.5,15.1 12,13.5 9.5,15.1 10.2,12.2 7.9,10.3 10.9,10.1"
                fill="#ffffff"
              />
            </svg>
            <span>{{ 'loginContinueSso' | translate }}</span>
          </button>

          <p class="login-support-prompt">
            <span>{{ 'loginNoAccount' | translate }}</span>
            <a routerLink="/account">{{ 'loginContactSupport' | translate }}</a>
          </p>
        </div>
      </section>
    </main>
  `,
})
export class Login {
  readonly form = inject(FormBuilder).nonNullable.group({
    login: ['', Validators.required],
    password: ['', Validators.required],
    remember: [false],
  });
  readonly busy = signal(false);
  readonly error = signal('');
  readonly passwordVisible = signal(false);
  readonly language = inject(Language);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  constructor() {
    const failure = inject(ActivatedRoute).snapshot.queryParamMap.get('failure');
    if (failure?.startsWith('errors.') && failure.slice(7) in en.errors) this.error.set(failure);
  }
  setLanguage(locale: 'fr-SN' | 'en') {
    this.error.set('');
    this.language
      .change(locale)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({ error: (error) => this.error.set(errorKey(error)) });
  }
  showSsoNotice() {
    this.error.set('loginSsoUnavailable');
  }
  submit() {
    if (this.form.invalid) {
      this.error.set('validation');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    const body = this.form.getRawValue();
    this.api
      .login(body.login, body.password)
      .pipe(
        switchMap((user) => this.language.load(user.locale)),
        finalize(() => this.busy.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: () => {
          this.form.reset();
          void this.router.navigate(['/']);
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
