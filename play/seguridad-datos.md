# Formulario de seguridad de los datos — NListener

Las respuestas para Play Console → Contenido de la app → Seguridad de los
datos. Salen de leer el código, no de suponer: el manifiesto
(`app/src/main/AndroidManifest.xml`), las dependencias
(`app/build.gradle.kts`), lo que la app manda
(`app/src/main/java/com/tomich/notificationlistener/data/ApiClient.kt`) y lo
que el servidor guarda (`src/app/api/notifications/route.ts` en el repo
`notification-listener`).

**Por qué importa que esto sea exacto:** Google compara lo que declarás con lo
que la app hace. Si no coincide, rechazan la revisión, y en el caso de datos
financieros la omisión es de las que pueden terminar en suspensión. Declarar
de más también molesta: asusta al usuario sin motivo.

> **Esto es una propuesta de respuestas, no un formulario enviado.** Los
> puntos marcados como **A CONFIRMAR** son los que el código no alcanza a
> responder y conviene preguntarle a soporte de Play antes de enviar.

Versión de referencia: `versionName` 2.5, `versionCode` 17.

## Lo primero que pregunta

**¿La app recopila o comparte alguno de los tipos de datos requeridos?**
→ **Sí**

**¿Los datos están cifrados en tránsito?** → **Sí**. Todo va por HTTPS con
OkHttp.

**¿Ofrecés una forma de solicitar la eliminación de los datos?** → **Sí**

**URL de eliminación de cuentas** → la página del panel:

```
https://www.nlistener.com.ar/delete-account
```

Conviene usar el dominio propio y no la URL de `*.vercel.app`: es el que la
app le muestra al usuario en la pantalla de configuración (`ui/SetupScreen.kt`).

**Verificado el 5 de noviembre de 2026:** la ruta responde 200. Ojo con el
`www`: el ápex `nlistener.com.ar` contesta 308 y redirige a
`www.nlistener.com.ar`, así que conviene cargar directamente la forma con
`www`, que es la definitiva.

**A CONFIRMAR:** que la página cumpla los tres requisitos de Google (nombrar
la app y al desarrollador, mostrar los pasos, y decir qué se borra y qué se
conserva).

**Métodos de creación de cuentas** → **A CONFIRMAR**. La app en sí **no** crea
cuentas ni tiene usuario y contraseña: se vincula escaneando un QR que trae un
token de dispositivo. La cuenta se crea en la web, con correo y contraseña
(Firebase Auth). Hay que preguntar si, para el formulario, cuenta como que la
app permite crear cuenta.

**¿Se pueden borrar datos sin eliminar la cuenta?** → **Sí**. Se puede
desvincular un dispositivo desde el panel, lo que corta el envío.

## Tipos de datos a declarar

Para todos: **recopilado sí**. Sobre *compartido*, ver la nota de abajo.

| Categoría | Tipo | Recopilado | Compartido | Obligatorio | Motivo |
|---|---|---|---|---|---|
| Información financiera | Otra información financiera | Sí | No | Sí | Funciones de la app |
| ID de dispositivo o de otro tipo | ID de dispositivo o de otro tipo | Sí | No | Sí | Funciones de la app |
| Registros de la app | Registros de bloqueo | Sí | No | No | Diagnóstico |
| Registros de la app | Diagnósticos | Sí | No | No | Diagnóstico |

### Información financiera: esta es la que no se puede omitir

La app lee el **texto completo** de las notificaciones de las apps de cobro que
el usuario selecciona (por defecto `com.mercadopago.wallet`, ver
`data/AppConstants.kt`) y lo envía al servidor, que lo guarda y además le
**extrae el importe** a un campo propio (`extractAmount`). Lo que viaja por
cada notificación es `app`, `text`, `timestamp` y `dedupeId`.

O sea: la app recopila importes de cobros, y el texto puede incluir el nombre
de quien pagó. Eso es información financiera, por mucho que la app no procese
ningún pago. **Es el dato central del producto y el que más caro sale omitir.**

Va marcado como **obligatorio** porque sin eso la app no hace nada.

### IDs

- El **token de dispositivo** que se configura con el QR.
- El **token de FCM**, que se manda a `/api/devices/fcm-token`.
- El **identificador de instalación** que usa Crashlytics.

### Registros de bloqueo y diagnósticos

`app/build.gradle.kts` trae `firebase-crashlytics-ktx` y el plugin
`com.google.firebase.crashlytics`, con `google-services.json` en su lugar. No
hay ninguna llamada a Crashlytics en el código, y no hace falta: **con la
dependencia y el plugin puestos, recoge los cierres inesperados solo.**

Recoge el tipo de error, el lugar del código, el modelo del dispositivo, la
versión de Android y un identificador de instalación. No incluye el contenido
de las notificaciones.

Va como **no obligatorio**, porque la app funciona igual sin eso.

### Sobre la columna "compartido"

Para Google, *compartir* es transferir datos a un tercero que los usa por
cuenta propia. Firebase, Vercel y MercadoPago son proveedores que procesan por
cuenta del operador, así que la respuesta correcta es **recopilado, no
compartido**. Es una confusión frecuente y conviene no equivocarla en ninguna
de las dos direcciones.

## El caso dudoso

**¿El texto de la notificación es además un "mensaje"?** La taxonomía de
Google tiene *Mensajes → SMS o MMS / Correos electrónicos / Otros mensajes
en la app*, y también *Actividad en la app → Otro contenido generado por el
usuario*.

Una notificación generada por otra app no es un mensaje del usuario ni
contenido que el usuario haya creado, así que la lectura razonable es **no
declararlo** y dejarlo cubierto por información financiera. Pero es
discutible, y en un dato sensible conviene preguntar antes que asumir.
**A CONFIRMAR con soporte.**

## Lo que NO hay que declarar

- **Ubicación**: no se pide ni se usa. No hay permisos de ubicación en el
  manifiesto.
- **Contactos, agenda, archivos**: no se accede.
- **Fotos y videos**: la cámara se usa **solo** para escanear el QR de
  configuración, y ML Kit lo resuelve dentro del teléfono. La imagen no se
  guarda ni se envía, así que no corresponde declarar fotos. Conviene poder
  explicarlo si lo preguntan, porque el permiso `CAMERA` sí se ve en el
  bundle.
- **Micrófono**: no hay permiso de audio.
- **Publicidad**: no hay anuncios ni identificadores publicitarios, ni
  ninguna librería de ads.
- **Analítica de uso**: no hay Firebase Analytics ni ninguna otra herramienta
  de analítica de producto. Crashlytics es lo único, y va declarado como
  bloqueos y diagnósticos, que es otra cosa.
- **Información personal (correo, nombre)**: la app no los pide. El correo se
  carga en la web al registrarse, no acá. Depende de cómo se resuelva la
  pregunta de creación de cuentas, más arriba.

## Permisos que va a ver Google en el bundle

Del manifiesto, con para qué sirve cada uno, que es lo que hay que poder
justificar:

| Permiso | Para qué |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Leer las notificaciones de las apps seleccionadas. Es el permiso central |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Enviar al panel y saber si hay conexión |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Mantener la escucha activa en segundo plano |
| `RECEIVE_BOOT_COMPLETED` | Volver a arrancar el servicio al reiniciar el teléfono |
| `WAKE_LOCK` | Seguir enviando con la pantalla apagada |
| `SCHEDULE_EXACT_ALARM` | Reintentos y verificación de que el servicio sigue vivo |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Pedirle al usuario la excepción de batería, sin la cual el OEM mata el servicio |
| `POST_NOTIFICATIONS` | El aviso propio de la app |
| `CAMERA` | Solo el escaneo del QR de configuración |

Dos que suelen pedir explicación aparte:

- **`FOREGROUND_SERVICE_SPECIAL_USE`** exige declarar el caso de uso y
  justificarlo. Acá es mantener viva la escucha de notificaciones, que no
  encaja en ninguno de los tipos predefinidos.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** es de las que Google mira con
  lupa. Hay que poder explicar que sin eso los OEM matan el servicio y la app
  deja de cumplir su única función.

## Política de privacidad

La URL que va en la ficha. **A CONFIRMAR** cuál está puesta hoy: conviene que
sea el dominio propio y no `*.vercel.app`.

```
https://www.nlistener.com.ar/privacy
```

Verificado el 5 de noviembre de 2026: responde 200, con el texto nuevo.

El texto fue reescrito en noviembre de 2026, porque el anterior estaba
desactualizado y decía que no se recolectaba ningún correo y que las
notificaciones iban a un servidor del propio usuario. **Estas respuestas y esa
página tienen que decir lo mismo**: si cambia una, hay que cambiar la otra.

## Clasificación de contenido y público objetivo

Sin violencia, sin contenido sexual, sin lenguaje adulto, sin apuestas. Hay
compras, pero fuera de la app: la suscripción se contrata en la web, no con
facturación de Play. **A CONFIRMAR** cómo se contesta eso en el cuestionario,
porque "compras digitales" suele referirse a la facturación de Play.

Público objetivo: mayores de 18, por ser una herramienta comercial, coherente
con lo que dice la política de privacidad.
