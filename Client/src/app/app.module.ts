import {BrowserModule} from '@angular/platform-browser';
import {NgModule} from '@angular/core';

import {AppRoutingModule} from './routing/app-routing.module';
import {AppComponent} from './app.component';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {MatToolbarModule} from '@angular/material/toolbar';
import {ToolbarModule} from './toolbar/toolbar.module';
import {DashboardModule} from './dashboard/dashboard.module';
import {CertificatesModule} from './certificates/certificates.module';
import {HTTP_INTERCEPTORS, HttpClientModule} from '@angular/common/http';
import {UrlInterceptor} from './interceptors/url-interceptor';
import {LogsModule} from './logs/logs.module';
import {LoginModule} from "./login/login.module";
import {TokenInterceptor} from "./interceptors/token-interceptor";


@NgModule({
  declarations: [
    AppComponent

  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule,
    MatToolbarModule,
    ToolbarModule,
    DashboardModule,
    CertificatesModule,
    HttpClientModule,
    LogsModule,
    LoginModule
  ],
  providers: [
    {provide: HTTP_INTERCEPTORS, useClass: UrlInterceptor, multi: true},
    {provide: HTTP_INTERCEPTORS, useClass: TokenInterceptor, multi: true}
  ],
  bootstrap: [AppComponent]
})
export class AppModule {
}
