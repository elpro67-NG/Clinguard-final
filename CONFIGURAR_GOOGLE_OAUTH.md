# Google OAuth2 en ClinGuard v12

Se conserva el login de usuario y contraseña y las cookies JWT actuales. La cookie de sesión usa `SameSite=Lax` para permitir el retorno desde Google en el flujo OAuth2.

1. En Google Cloud Console, configura la pantalla de consentimiento OAuth (usuarios externos o internos según corresponda) y crea un **cliente OAuth2 de tipo Aplicación web**. Añade esta URI de redirección autorizada: `http://localhost:8080/login/oauth2/code/google`.
2. En PowerShell establece las variables en la sesión donde ejecutarás ClinGuard:
   ```powershell
   $env:GOOGLE_CLIENT_ID="TU_CLIENT_ID.apps.googleusercontent.com"
   $env:GOOGLE_CLIENT_SECRET="TU_CLIENT_SECRET"
   $env:GOOGLE_OAUTH_ENABLED="true"
   ```
3. Inicia la aplicación como antes y visita `http://localhost:8080/login`. Pulsa **Continuar con Google**.
4. Usuarios nuevos verificados se crean en MongoDB con rol `USUARIO`. Los usuarios existentes con el mismo correo deben iniciar sesión de manera tradicional: no se enlazan automáticamente; implementar una vinculación desde una sesión autenticada si la necesitas.
5. Verifica que un usuario Google se dirija a `/dashboard`, que se establezca `JWT_TOKEN`, que cierre sesión correctamente y que se respeten sus permisos. En producción configura el dominio HTTPS, el redirect correspondiente, `app.jwt.cookie-secure=true` y cookies de sesión seguras.

**Seguridad:** no uses el Client Secret mostrado en la guía compartida: ya está expuesto en ese archivo. Revócalo/gíralo en Google Cloud, genera uno nuevo y mantenlo únicamente en variables de entorno o un gestor de secretos. No publiques estos valores en Git.

Para desactivar el acceso con Google sin modificar el proyecto, usa `GOOGLE_OAUTH_ENABLED=false` (valor por defecto).
