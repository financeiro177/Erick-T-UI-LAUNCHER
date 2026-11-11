package ohi.andre.consolelauncher.commands.main.raw;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
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
                return new int[] {CommandAbstraction.NO_SPACE_STRING};
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
                return new int[] {CommandAbstraction.NO_SPACE_STRING};
            }

            @Override
            public String exec(ExecutePack pack) {
                Context c = pack.context;
                MockLocationStore.Place p = MockLocationStore.place(c, LocAddPrompt.normalize(pack.getString()));
                if (p == null) return say(c, "loc: local não encontrado (loc -ls)");

                MockLocationStore.activate(c, p);
                return startService(c, "ativo: " + p.name);
            }
        },
        go {
            @Override
            public int[] args() {
                return new int[] {CommandAbstraction.NO_SPACE_STRING, CommandAbstraction.INT};
            }

            @Override
            public String exec(ExecutePack pack) {
                final Context c = pack.context;
                final MockLocationStore.Place target = MockLocationStore.place(c, LocAddPrompt.normalize(pack.getString()));
                final int minutes = pack.getInt();

                if (target == null) return say(c, "loc: local não encontrado (loc -ls)");
                if (minutes < 1 || minutes > 24 * 60) return say(c, "loc: minutos entre 1 e 1440");

                final long duration = minutes * 60L * 1000L;

                MockLocationStore.State s = MockLocationStore.state(c);
                if (s.isOn() && s.hasPosition) {
                    // already mocking: leave from the point being emitted right now
                    MockLocationStore.startGo(c, target, s.lat, s.lon, s.alt, duration);
                    return startService(c, "indo para " + target.name + " em " + minutes + " min");
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
                        MockLocationStore.startGo(c, target, l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : target.alt, duration);
                        startService(c, "indo para " + target.name + " em " + minutes + " min");
                    }
                });
                return null;
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

    /** Prints a line in cyan. Returns null so the caller can "return say(...)" without printing twice. */
    private static String say(Context c, String text) {
        Tuils.sendOutput(MockLocationService.CYAN, c, text);
        return null;
    }

    /** Stops the mock: the test providers are removed and the other apps see the real GPS again. */
    private static String turnOff(Context c) {
        MockLocationStore.clear(c);
        try {
            MockLocationService.stop(c);
        } catch (Exception ignored) {}
        return say(c, "mock desligado, GPS real");
    }

    private static String startService(Context c, String okMessage) {
        if (!MockLocationService.isMockAppSelected(c)) {
            MockLocationStore.clear(c);
            MockLocationService.sendSelectAppMessage(c);
            return null;
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
