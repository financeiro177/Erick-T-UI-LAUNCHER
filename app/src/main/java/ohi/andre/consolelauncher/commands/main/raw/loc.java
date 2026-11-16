package ohi.andre.consolelauncher.commands.main.raw;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;

import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.LauncherActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.main.specific.ParamCommand;
import ohi.andre.consolelauncher.managers.mock.LocAddPrompt;
import ohi.andre.consolelauncher.managers.mock.MockLocationService;
import ohi.andre.consolelauncher.managers.mock.MockLocationStore;
import ohi.andre.consolelauncher.managers.mock.RealLocation;
import ohi.andre.consolelauncher.managers.mock.RouteFetcher;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * Mock location controlled from the terminal.
 * The command only writes MockLocationStore and starts/stops MockLocationService.
 */
public class loc extends ParamCommand {

    private enum Param implements ohi.andre.consolelauncher.commands.main.Param {

        add {
            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;

                if (!RealLocation.hasPermission(c)) {
                    try {
                        ActivityCompat.requestPermissions((Activity) c, new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
                                LauncherActivity.COMMAND_REQUEST_PERMISSION);
                    } catch (Exception ignored) {}
                    return say(c, "loc: permita a localização e repita o comando");
                }

                if (MockLocationStore.state(c).isOn()) return say(c, "loc: desligue o mock antes (loc -reset)");

                // the next line typed goes to LocAddPrompt.onRedirect
                ((MainPack) pack).redirectator.prepareRedirection(new LocAddPrompt());
                return say(c, "nome do local?");
            }
        },
        rm {
            @Override
            public int[] args() {
                return new int[] {CommandAbstraction.MOCK_PLACE};
            }

            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;
                String name = LocAddPrompt.normalize(pack.getString());
                if (name == null) return say(c, "loc: nome inválido");

                MockLocationStore.State s = MockLocationStore.state(c);
                if (name.equals(s.active) || name.equals(s.goTarget)) return say(c, "loc: " + name + " está em uso (loc -reset)");

                return say(c, MockLocationStore.remove(c, name) ? "removido: " + name : "loc: não existe: " + name);
            }
        },
        ls {
            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;
                List<MockLocationStore.Place> places = MockLocationStore.places(c);
                if (places.isEmpty()) return say(c, "loc: nenhum local salvo (loc -add)");

                MockLocationStore.State s = MockLocationStore.state(c);
                StringBuilder b = new StringBuilder();
                for (MockLocationStore.Place p : places) {
                    String mark = p.name.equals(s.active) ? "* " : p.name.equals(s.goTarget) ? "> " : "  ";
                    b.append(mark).append(p.name).append(String.format(Locale.US, "  %.5f, %.5f", p.lat, p.lon)).append(Tuils.NEWLINE);
                }
                b.append(s.isOn() ? "(* ativo, > a caminho)" : "(mock desligado)");
                return say(c, b.toString());
            }
        },
        use {
            @Override
            public int[] args() {
                return new int[] {CommandAbstraction.MOCK_PLACE};
            }

            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;
                MockLocationStore.Place p = MockLocationStore.place(c, LocAddPrompt.normalize(pack.getString()));
                if (p == null) return say(c, "loc: local não encontrado (loc -ls)");

                tripSeq++;
                MockLocationStore.activate(c, p);
                return startService(c, "ativo: " + p.name);
            }
        },
        at {
            // "loc -at -23.55052 -46.63331" or "loc -at -23.55052,-46.63331": mock raw coordinates without saving a place
            @Override
            public int[] args() {
                return new int[] {CommandAbstraction.PLAIN_TEXT};
            }

            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;

                double[] ll = parseCoords(c, pack.getString(), "loc -at latitude longitude (ex.: loc -at -23.55052 -46.63331)");
                if (ll == null) return null;

                tripSeq++;
                MockLocationStore.activate(c, new MockLocationStore.Place(MANUAL, ll[0], ll[1], 0));
                return startService(c, String.format(Locale.US, "ativo: %.5f, %.5f", ll[0], ll[1]));
            }
        },
        save {
            // "loc -save casa -23.55052 -46.63331": save a place from coordinates picked on a map
            @Override
            public int[] args() {
                return new int[] {CommandAbstraction.NO_SPACE_STRING, CommandAbstraction.PLAIN_TEXT};
            }

            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;

                String name = LocAddPrompt.normalize(pack.getString());
                if (name == null) return say(c, "loc: nome inválido");

                double[] ll = parseCoords(c, pack.getString(), "loc -save nome latitude longitude (ex.: loc -save casa -23.55052 -46.63331)");
                if (ll == null) return null;

                boolean existed = MockLocationStore.place(c, name) != null;
                if (!MockLocationStore.save(c, new MockLocationStore.Place(name, ll[0], ll[1], 0))) return say(c, "loc: erro ao salvar");

                return say(c, String.format(Locale.US, "%s: %s (%.5f, %.5f)", existed ? "atualizado" : "salvo", name, ll[0], ll[1]));
            }
        },
        go {
            // minutes are optional: without them the duration comes from the distance (road route when enabled, else FALLBACK_KMH)
            @Override
            public int[] args() {
                return new int[] {CommandAbstraction.MOCK_PLACE, CommandAbstraction.INT};
            }

            @Override
            public String exec(ExecutePack pack) {
                String name = pack.getString();
                int minutes = pack.getInt();
                if (minutes < 1 || minutes > 24 * 60) return say(pack.context, "loc: minutos entre 1 e 1440");

                return startTrip(pack.context, name, minutes);
            }

            @Override
            public String onNotArgEnough(ExecutePack pack, int n) {
                // n counts the option itself: 2 = "-go name", without the minutes
                Object name = n == 2 ? pack.get(Object.class, 1) : null;
                if (name == null) return pack.context.getString(R.string.help_loc);

                return startTrip(pack.context, name.toString(), 0);
            }
        },
        off {
            @Override
            public String exec(ExecutePack pack) {
                return turnOff(pack.context);
            }
        },
        reset {
            // same as -off
            @Override
            public String exec(ExecutePack pack) {
                return turnOff(pack.context);
            }
        },
        status {
            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;
                MockLocationStore.State s = MockLocationStore.state(c);

                StringBuilder b = new StringBuilder();
                if (s.goTarget != null) {
                    long left = Math.max(0, s.goStart + s.goDuration - System.currentTimeMillis()) / 1000;
                    b.append("local: a caminho de ").append(s.goTarget).append(" (").append(left / 60).append("m").append(left % 60).append("s)");
                } else {
                    b.append("local: ").append(s.active != null ? s.active : "-");
                }
                if (s.isOn() && s.hasPosition) b.append(String.format(Locale.US, "  %.5f, %.5f", s.lat, s.lon));

                b.append(Tuils.NEWLINE).append("serviço: ").append(MockLocationService.running ? "rodando" : "parado");
                b.append(Tuils.NEWLINE).append("app de mock: ").append(MockLocationService.isMockAppSelected(c) ? "selecionado" : "NÃO selecionado (loc -dev)");
                b.append(Tuils.NEWLINE).append("permissão de localização: ").append(RealLocation.hasPermission(c) ? "ok" : "negada");

                try {
                    PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                    if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        b.append(Tuils.NEWLINE).append("bateria: ").append(pm.isIgnoringBatteryOptimizations(c.getPackageName())
                                ? "sem restrição" : "otimizada (o sistema pode parar o serviço)");
                    }
                } catch (Exception ignored) {}

                return say(c, b.toString());
            }
        },
        dev {
            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                    return null;
                } catch (Exception e) {
                    return say(c, "loc: ative as Opções do desenvolvedor primeiro");
                }
            }
        };

        @Override
        public int[] args() {
            return new int[0];
        }

        static Param get(String p) {
            p = p.toLowerCase();
            Param[] ps = values();
            for (Param p1 : ps)
                if (p.endsWith(p1.label()))
                    return p1;
            return null;
        }

        static String[] labels() {
            Param[] ps = values();
            String[] ss = new String[ps.length];

            for (int count = 0; count < ps.length; count++) {
                ss[count] = ps[count].label();
            }

            return ss;
        }

        @Override
        public String label() {
            return Tuils.MINUS + name();
        }

        @Override
        public String onNotArgEnough(ExecutePack pack, int n) {
            return pack.context.getString(R.string.help_loc);
        }

        @Override
        public String onArgNotFound(ExecutePack pack, int index) {
            return pack.context.getString(R.string.help_loc);
        }
    }

    /** Name shown as the active place while raw coordinates ("-at") are being mocked. */
    private static final String MANUAL = "(manual)";

    // bumped by every command that changes what is being mocked, so a route still being fetched is dropped
    private static volatile int tripSeq = 0;

    // Road routes need an external routing server (RouteFetcher), which receives the start and destination.
    // Off for now: "-go" moves in a straight line and nothing is sent anywhere.
    private static final boolean ROADS_ENABLED = false;

    // straight-line trips without minutes (no route available) assume this speed
    private static final double FALLBACK_KMH = 40;

    /** Parses "lat lon" or "lat,lon"; prints the reason and returns null when it is not a valid pair. */
    private static double[] parseCoords(Context c, String text, String usage) {
        String[] parts = text == null ? new String[0] : text.trim().split("[\\s,;]+");
        if (parts.length != 2) {
            say(c, "loc: use " + usage);
            return null;
        }

        double lat, lon;
        try {
            lat = Double.parseDouble(parts[0]);
            lon = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            say(c, "loc: coordenadas inválidas (use ponto como decimal)");
            return null;
        }
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            say(c, "loc: latitude entre -90 e 90, longitude entre -180 e 180");
            return null;
        }
        return new double[] {lat, lon};
    }

    /** "-go": finds where the trip starts (the point being mocked, or the real position) and plans it. */
    private static String startTrip(final Context c, String name, final int minutes) {
        final MockLocationStore.Place target = MockLocationStore.place(c, LocAddPrompt.normalize(name));
        if (target == null) return say(c, "loc: local não encontrado (loc -ls)");

        final int seq = ++tripSeq;

        MockLocationStore.State s = MockLocationStore.state(c);
        if (s.isOn() && s.hasPosition) {
            // already mocking: leave from the point being emitted right now
            planTrip(c, target, s.lat, s.lon, s.alt, minutes, seq);
            return null;
        }

        // mock off: leave from the real position
        if (!RealLocation.hasPermission(c)) return say(c, "loc: sem permissão de localização");
        RealLocation.get(c, new RealLocation.Callback() {
            @Override
            public void onResult(Location l) {
                if (l == null) {
                    say(c, "loc: sem posição atual; use loc -use antes");
                    return;
                }
                planTrip(c, target, l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : target.alt, minutes, seq);
            }
        });
        return null;
    }

    /** Fetches the road route in the background and starts the trip; without a route it goes in a straight line. */
    private static void planTrip(final Context c, final MockLocationStore.Place target, final double startLat, final double startLon,
                                 final double startAlt, final int minutes, final int seq) {
        if (ROADS_ENABLED) say(c, "loc: calculando rota...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                RouteFetcher.Route route = ROADS_ENABLED ? RouteFetcher.fetch(startLat, startLon, target.lat, target.lon) : null;

                // another loc command arrived while the route was being fetched
                if (seq != tripSeq) return;

                long duration;
                String path = null, message;
                if (route != null) {
                    path = route.encode();
                    duration = minutes > 0 ? minutes * 60000L : Math.round(route.durationSeconds * 1000);
                    message = String.format(Locale.US, "indo para %s por estradas: %.1f km em %d min", target.name,
                            route.distanceMeters / 1000, Math.max(1, Math.round(duration / 60000d)));
                } else {
                    float[] r = new float[1];
                    Location.distanceBetween(startLat, startLon, target.lat, target.lon, r);
                    duration = minutes > 0 ? minutes * 60000L : Math.max(60000L, Math.round(r[0] / (FALLBACK_KMH / 3.6) * 1000));
                    message = String.format(Locale.US, (ROADS_ENABLED ? "sem rota disponível: " : "") + "indo para %s em linha reta, %.1f km em %d min", target.name,
                            r[0] / 1000, Math.max(1, Math.round(duration / 60000d)));
                }

                MockLocationStore.startGo(c, target, startLat, startLon, startAlt, duration, path);
                startService(c, message);
            }
        }, "loc-route").start();
    }

    /** Prints a line in cyan. Returns null so the caller can "return say(...)" without printing twice. */
    private static String say(Context c, String text) {
        Tuils.sendOutput(MockLocationService.CYAN, c, text);
        return null;
    }

    /** Stops the mock: the test providers are removed and the other apps see the real GPS again. */
    private static String turnOff(Context c) {
        tripSeq++;
        MockLocationStore.clear(c);
        try {
            MockLocationService.stop(c);
        } catch (Exception ignored) {}
        // also when the service was already dead, so nothing stays stuck replacing the real providers
        MockLocationService.releaseProviders(c);
        MockLocationService.releaseFused(c);

        if (!MockLocationService.isMockAppSelected(c)) {
            // without the selection the system refuses the cleanup above
            say(c, "loc: selecione este app como localização fictícia (loc -dev) e repita o loc -reset; se a posição real não voltar, reinicie o aparelho");
        }

        say(c, "mock desligado, buscando a posição real...");
        confirmRealLocation(c);
        return null;
    }

    /** After the mock is turned off: wakes the real providers up and tells when the real position is back. */
    private static void confirmRealLocation(final Context c) {
        if (!RealLocation.hasPermission(c)) {
            say(c, "loc: sem permissão de localização para conferir a posição real");
            return;
        }

        final int seq = tripSeq;

        // a moment for the test providers to be removed and for Google's provider to leave the mock mode
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                RealLocation.get(c, new RealLocation.Callback() {
                    @Override
                    public void onResult(Location l) {
                        // the mock was turned on again meanwhile
                        if (seq != tripSeq) return;

                        if (l == null) {
                            say(c, "loc: ainda sem posição real. Em ambiente fechado o GPS pode levar alguns minutos; a Precisão de local do Google ajuda");
                        } else {
                            say(c, String.format(Locale.US, "posição real de volta: %.5f, %.5f (±%.0f m)", l.getLatitude(), l.getLongitude(), l.getAccuracy()));
                        }
                    }
                });
            }
        }, 2000);
    }

    private static String startService(Context c, String okMessage) {
        if (!MockLocationService.isMockAppSelected(c)) {
            // the state stays saved: the mock starts by itself once the app is selected
            MockLocationService.sendSelectAppMessage(c);
            return say(c, "loc: pendente até selecionar o app (" + okMessage + ")");
        }

        try {
            MockLocationService.start(c);
        } catch (Exception e) {
            MockLocationStore.clear(c);
            return say(c, "loc: não foi possível iniciar o serviço");
        }
        return say(c, okMessage);
    }

    @Override
    protected ohi.andre.consolelauncher.commands.main.Param paramForString(MainPack pack, String param) {
        return Param.get(param);
    }

    @Override
    protected String doThings(ExecutePack pack) {
        return null;
    }

    @Override
    public String[] params() {
        return Param.labels();
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public int helpRes() {
        return R.string.help_loc;
    }
}
