package paragon;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.io.Serializable;
import java.io.IOException;
import java.util.*;

public class Dummy extends TeamRobot {

    // ==========================================
    // CONFIGURAÇÕES
    // ==========================================

    private static final double WALL_MARGIN = 85;
    private static final double ROBOT_RADIUS = 18;
    private static final double MAX_VELOCITY = 8;

    private static final int MAX_ENEMY_AGE = 45;
    private static final int TEAM_INFO_AGE = 35;
    private static final int TEAM_POSITION_AGE = 12;

    private static final double TARGET_SWITCH_MARGIN = 35;

    private static final double MIN_TEAM_DISTANCE = 155;

    private static final double TEAM_FIRE_MARGIN = 70;

    private int direction = 1;
    private String currentTarget = null;

    private long lastBroadcast = -100;
    private long lastPositionBroadcast = -20;
    private long lastTargetBroadcast = -1000;

    private double guardianX;
    private double guardianY;
    private long lastGuardianPosition = -1000;

    private double allyEscapeAngle;
    private long allyEscapeUntil = -1;

    private final Map<String, EnemyData> enemies =
        new HashMap<String, EnemyData>();

    private final Map<String, TeamIntel> teamIntel =
        new HashMap<String, TeamIntel>();

    // ==========================================
    // INFORMAÇÕES DOS INIMIGOS
    // ==========================================

    private static class EnemyData {

        double x, y;
        double heading;
        double velocity;
        double energy;
        double turnRate;
        double distance;
        long lastSeen;

        EnemyData(ScannedRobotEvent e,
                  double x, double y, long time) {

            update(e, x, y, time);
        }

        void update(ScannedRobotEvent e,
                    double newX, double newY,
                    long time) {

            long elapsed = time - lastSeen;

            if (lastSeen > 0 && elapsed > 0) {

                turnRate = Utils.normalRelativeAngle(
                    e.getHeadingRadians() - heading
                ) / elapsed;

                turnRate = Math.max(
                    -0.2,
                    Math.min(0.2, turnRate)
                );
            }

            x = newX;
            y = newY;
            heading = e.getHeadingRadians();
            velocity = e.getVelocity();
            energy = e.getEnergy();
            distance = e.getDistance();
            lastSeen = time;
        }
    }

    // ==========================================
    // COMUNICAÇÃO ENTRE ALIADOS
    // ==========================================

    // Mensagem contendo informações dos inimigos.
    public static class EnemyReport implements Serializable {

        private static final long serialVersionUID = 1L;

        public String sender;
        public String enemy;
        public double x, y, energy;
        public long time;

        public EnemyReport(String sender, String enemy,
                           double x, double y,
                           double energy, long time) {

            this.sender = sender;
            this.enemy = enemy;
            this.x = x;
            this.y = y;
            this.energy = energy;
            this.time = time;
        }
    }

    // Mensagem contendo a posição do robô aliado.
    // Dummy e Guardian utilizam a mesma classe.
    public static class PositionReport implements Serializable {

        private static final long serialVersionUID = 1L;

        public String sender;
        public double x;
        public double y;
        public long time;

        public PositionReport(String sender,
                              double x,
                              double y,
                              long time) {

            this.sender = sender;
            this.x = x;
            this.y = y;
            this.time = time;
        }
    }

    // Mensagem contendo o inimigo escolhido pelo Dummy.
    public static class TargetReport implements Serializable {

        private static final long serialVersionUID = 1L;

        public String sender;
        public String target;
        public double x, y, energy;
        public long time;

        public TargetReport(String sender, String target,
                            double x, double y,
                            double energy, long time) {

            this.sender = sender;
            this.target = target;
            this.x = x;
            this.y = y;
            this.energy = energy;
            this.time = time;
        }
    }

    private static class TeamIntel {

        double x, y, energy;
        long time;
    }

    // ==========================================
    // COMPARTILHAR INFORMAÇÕES DOS INIMIGOS
    // ==========================================

    private void compartilharInformacao(String enemy,
                                         EnemyData data) {

        if (getTime() - lastBroadcast < 5) {
            return;
        }

        try {

            broadcastMessage(
                new EnemyReport(
                    getName(),
                    enemy,
                    data.x,
                    data.y,
                    data.energy,
                    getTime()
                )
            );

            lastBroadcast = getTime();

        } catch (IOException ex) {
            // Falha de comunicação não interrompe o combate.
        }
    }

    // ==========================================
    // COMPARTILHAR POSIÇÃO DO DUMMY
    // ==========================================

    private void compartilharPosicao() {

        if (getTime() - lastPositionBroadcast < 4) {
            return;
        }

        try {

            broadcastMessage(
                new PositionReport(
                    getName(),
                    getX(),
                    getY(),
                    getTime()
                )
            );

            lastPositionBroadcast = getTime();

        } catch (IOException ex) {
            // Falha de comunicação não interrompe o combate.
        }
    }

    // ==========================================
    // COMPARTILHAR ALVO PRIORITÁRIO
    // ==========================================

    private void compartilharAlvo() {

        // Evita enviar mensagens em excesso.
        if (getTime() - lastTargetBroadcast < 3) {
            return;
        }

        EnemyData data = enemies.get(currentTarget);

        try {

            // Avisa quando o Dummy ainda não escolheu um alvo.
            if (data == null) {

                broadcastMessage(
                    new TargetReport(
                        getName(),
                        null,
                        0,
                        0,
                        0,
                        getTime()
                    )
                );

            } else {

                // Informa ao Guardian qual inimigo atacar.
                broadcastMessage(
                    new TargetReport(
                        getName(),
                        currentTarget,
                        data.x,
                        data.y,
                        data.energy,
                        getTime()
                    )
                );
            }

            lastTargetBroadcast = getTime();

        } catch (IOException ex) {
            // Falha de comunicação não interrompe o combate.
        }
    }

    // ==========================================
    // EVITAR COLISÕES COM O GUARDIAN
    // ==========================================

    private boolean evitarColisaoAliado() {

        // Se recebeu uma posição recente do Guardian,
        // verifica se os dois robôs estão próximos demais.
        if (getTime() >= allyEscapeUntil &&
            getTime() - lastGuardianPosition <= TEAM_POSITION_AGE) {

            double distancia = Math.hypot(
                guardianX - getX(),
                guardianY - getY()
            );

            if (distancia < MIN_TEAM_DISTANCE) {

                double dx = getX() - guardianX;
                double dy = getY() - guardianY;

                // Se estiverem praticamente no mesmo ponto,
                // utiliza uma direção oposta ao movimento atual.
                if (Math.hypot(dx, dy) < 1) {
                    allyEscapeAngle = getHeadingRadians() + Math.PI;
                } else {
                    allyEscapeAngle = Math.atan2(dx, dy);
                }

                // Mantém o afastamento por alguns turnos.
                allyEscapeUntil = getTime() + 8;
            }
        }

        // Continua a manobra iniciada por proximidade ou colisão.
        if (getTime() < allyEscapeUntil) {

            double giro = Utils.normalRelativeAngle(
                allyEscapeAngle - getHeadingRadians()
            );

            setTurnRightRadians(giro);
            setAhead(120);

            return true;
        }

        return false;
    }

    // ==========================================
    // RECEBER INFORMAÇÕES DOS ALIADOS
    // ==========================================

    public void onMessageReceived(MessageEvent e) {

        Object message = e.getMessage();

        // Recebe a posição do Guardian.
        if (message instanceof PositionReport) {

            PositionReport report = (PositionReport) message;

            if (!isTeammate(report.sender)) {
                return;
            }

            long age = getTime() - report.time;

            if (age >= 0 && age <= TEAM_POSITION_AGE &&
                report.time >= lastGuardianPosition) {

                guardianX = report.x;
                guardianY = report.y;
                lastGuardianPosition = report.time;
            }

            return;
        }

        // Recebe informações sobre inimigos detectados pelo Guardian.
        if (message instanceof EnemyReport) {

            EnemyReport report = (EnemyReport) message;

            if (!isTeammate(report.sender)) {
                return;
            }

            if (report.enemy == null || isTeammate(report.enemy)) {
                return;
            }

            long age = getTime() - report.time;

            if (age < 0 || age > TEAM_INFO_AGE) {
                return;
            }

            TeamIntel old = teamIntel.get(report.enemy);

            if (old == null || report.time >= old.time) {

                TeamIntel info = new TeamIntel();

                info.x = report.x;
                info.y = report.y;
                info.energy = report.energy;
                info.time = report.time;

                teamIntel.put(report.enemy, info);
            }
        }
    }

    // ==========================================
    // INICIALIZAÇÃO
    // ==========================================

    public void run() {

        setBodyColor(Color.BLUE);
        setGunColor(Color.WHITE);
        setRadarColor(Color.GREEN);
        setBulletColor(Color.CYAN);

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        setMaxVelocity(MAX_VELOCITY);
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);

        while (true) {

            // Remove informações antigas e atualiza o alvo.
            limparInformacoes();

            // Compartilha a posição e o alvo escolhido.
            compartilharPosicao();
            compartilharAlvo();

            // Primeiro, verifica se precisa se afastar do Guardian.
            boolean fugindoDoAliado = evitarColisaoAliado();

            if (!fugindoDoAliado && currentTarget == null) {

                TeamIntel info = encontrarInformacaoRecente();

                if (info != null) {

                    double angle = Math.atan2(
                        info.x - getX(),
                        info.y - getY()
                    );

                    double error = Utils.normalRelativeAngle(
                        angle - getRadarHeadingRadians()
                    );

                    setTurnRadarRightRadians(error * 2);

                } else {

                    setTurnRight(45 * direction);
                    setAhead(100 * direction);

                    setTurnRadarRightRadians(
                        Double.POSITIVE_INFINITY
                    );
                }
            }

            execute();
        }
    }

    // ==========================================
    // ESCANEAMENTO
    // ==========================================

    public void onScannedRobot(ScannedRobotEvent e) {

        if (isTeammate(e.getName())) {
            return;
        }

        long now = getTime();

        double absoluteAngle =
            getHeadingRadians() + e.getBearingRadians();

        double enemyX = getX()
            + Math.sin(absoluteAngle) * e.getDistance();

        double enemyY = getY()
            + Math.cos(absoluteAngle) * e.getDistance();

        EnemyData data = enemies.get(e.getName());

        if (data == null) {

            data = new EnemyData(e, enemyX, enemyY, now);
            enemies.put(e.getName(), data);

        } else {

            data.update(e, enemyX, enemyY, now);
        }

        compartilharInformacao(e.getName(), data);

        selecionarAlvo();

        double radarError = Utils.normalRelativeAngle(
            absoluteAngle - getRadarHeadingRadians()
        );

        setTurnRadarRightRadians(
            radarError * 2
            + Math.signum(radarError) * 0.02
        );

        if (!e.getName().equals(currentTarget)) {
            return;
        }

        atacar(e, data);
        movimentar(e, data);
    }

    // ==========================================
    // SELEÇÃO INTELIGENTE E ESTÁVEL DE ALVO
    // ==========================================

    private void selecionarAlvo() {

        double bestScore = Double.POSITIVE_INFINITY;
        String bestEnemy = null;

        for (Map.Entry<String, EnemyData> entry : enemies.entrySet()) {

            EnemyData enemy = entry.getValue();

            if (getTime() - enemy.lastSeen > MAX_ENEMY_AGE) {
                continue;
            }

            double score = enemy.distance + enemy.energy * 1.5;

            if (enemy.energy <= 10) {
                score -= 35;
            }

            if (score < bestScore) {
                bestScore = score;
                bestEnemy = entry.getKey();
            }
        }

        EnemyData current = enemies.get(currentTarget);

        if (current != null &&
            getTime() - current.lastSeen <= MAX_ENEMY_AGE &&
            bestEnemy != null &&
            !bestEnemy.equals(currentTarget)) {

            double currentScore =
                current.distance + current.energy * 1.5;

            if (current.energy <= 10) {
                currentScore -= 35;
            }

            if (bestScore + TARGET_SWITCH_MARGIN >= currentScore) {
                return;
            }
        }

        currentTarget = bestEnemy;
    }

    private void limparInformacoes() {

        Iterator<Map.Entry<String, EnemyData>> iterator =
            enemies.entrySet().iterator();

        while (iterator.hasNext()) {

            Map.Entry<String, EnemyData> entry = iterator.next();

            if (getTime() - entry.getValue().lastSeen > MAX_ENEMY_AGE) {
                iterator.remove();
            }
        }

        Iterator<Map.Entry<String, TeamIntel>> teamIterator =
            teamIntel.entrySet().iterator();

        while (teamIterator.hasNext()) {

            if (getTime() - teamIterator.next().getValue().time
                > TEAM_INFO_AGE) {

                teamIterator.remove();
            }
        }

        if (currentTarget != null && !enemies.containsKey(currentTarget)) {
            currentTarget = null;
        }

        selecionarAlvo();
    }

    private TeamIntel encontrarInformacaoRecente() {

        TeamIntel best = null;

        for (TeamIntel info : teamIntel.values()) {

            if (best == null || info.time > best.time) {
                best = info;
            }
        }

        return best;
    }

    // ==========================================
    // MIRA PREDITIVA AVANÇADA
    // ==========================================

    private void atacar(ScannedRobotEvent e,
                        EnemyData data) {

        double distance = e.getDistance();

        double power = calcularPotencia(
            distance,
            e.getEnergy()
        );

        double bulletSpeed = 20 - 3 * power;

        double predictedX = data.x;
        double predictedY = data.y;

        double heading = data.heading;

        for (int iteration = 0; iteration < 5; iteration++) {

            double distanceToTarget = Math.hypot(
                predictedX - getX(), predictedY - getY()
            );

            int ticks = (int) Math.ceil(distanceToTarget / bulletSpeed);
            ticks = Math.min(ticks, 80);

            predictedX = data.x;
            predictedY = data.y;
            heading = data.heading;

            for (int t = 0; t < ticks; t++) {

                heading += data.turnRate;

                double nextX = predictedX
                    + Math.sin(heading) * data.velocity;

                double nextY = predictedY
                    + Math.cos(heading) * data.velocity;

                if (nextX < ROBOT_RADIUS ||
                    nextX > getBattleFieldWidth() - ROBOT_RADIUS ||
                    nextY < ROBOT_RADIUS ||
                    nextY > getBattleFieldHeight() - ROBOT_RADIUS) {

                    break;
                }

                predictedX = nextX;
                predictedY = nextY;
            }
        }

        double aimAngle = Math.atan2(
            predictedX - getX(), predictedY - getY()
        );

        double gunError = Utils.normalRelativeAngle(
            aimAngle - getGunHeadingRadians()
        );

        setTurnGunRightRadians(gunError);

        // Só dispara se o Guardian não estiver na trajetória.
        if (Math.abs(gunError) < Math.toRadians(2.2)
            && getGunHeat() == 0
            && getEnergy() > power + 0.15
            && !guardianBloqueiaTiro(predictedX, predictedY)) {

            setFire(power);
        }
    }

    // ==========================================
    // VERIFICAR SE O GUARDIAN BLOQUEIA O DISPARO
    // ==========================================

    private boolean guardianBloqueiaTiro(double alvoX, double alvoY) {

        if (getTime() - lastGuardianPosition > TEAM_POSITION_AGE) {
            return false;
        }

        // Calcula a trajetória entre o Dummy e o inimigo.
        double dx = alvoX - getX();
        double dy = alvoY - getY();

        double comprimentoQuadrado = dx * dx + dy * dy;

        if (comprimentoQuadrado < 1) {
            return true;
        }

        // Calcula a posição do Guardian em relação à trajetória.
        double posicao = (
            (guardianX - getX()) * dx
            + (guardianY - getY()) * dy
        ) / comprimentoQuadrado;

        // O aliado só bloqueia o tiro se estiver entre os dois robôs.
        if (posicao <= 0 || posicao >= 1) {
            return false;
        }

        double pontoX = getX() + posicao * dx;
        double pontoY = getY() + posicao * dy;

        double distancia = Math.hypot(
            guardianX - pontoX,
            guardianY - pontoY
        );

        return distancia < TEAM_FIRE_MARGIN;
    }

    // ==========================================
    // CONTROLE DE POTÊNCIA
    // ==========================================

    private double calcularPotencia(double distance,
                                    double enemyEnergy) {

        double power;

        if (distance < 130) {
            power = 3.0;
        } else if (distance < 250) {
            power = 2.5;
        } else if (distance < 400) {
            power = 2.0;
        } else if (distance < 550) {
            power = 1.5;
        } else {
            power = 1.0;
        }

        if (getEnergy() < 30) {
            power = Math.min(power, 1.5);
        }

        if (getEnergy() < 15) {
            power = Math.min(power, 1.0);
        }

        if (enemyEnergy < 3) {
            power = Math.min(power, enemyEnergy);
        }

        return Math.max(0.1, Math.min(3.0, power));
    }

    // ==========================================
    // MOVIMENTAÇÃO DE COMBATE
    // ==========================================

    private void movimentar(ScannedRobotEvent e,
                            EnemyData enemy) {

        // Não interrompe a manobra de afastamento do aliado.
        if (evitarColisaoAliado()) {
            return;
        }

        double enemyAngle = Math.atan2(
            enemy.x - getX(), enemy.y - getY()
        );

        double distance = e.getDistance();
        double desiredAngle;

        if (distance < 160) {

            desiredAngle =
                enemyAngle + Math.PI + direction * 0.6;

        } else if (distance > 450) {

            desiredAngle =
                enemyAngle + direction * 0.45;

        } else {

            desiredAngle =
                enemyAngle + direction * Math.PI / 2;
        }

        desiredAngle = wallSmoothing(desiredAngle, direction);

        double turn = Utils.normalRelativeAngle(
            desiredAngle - getHeadingRadians()
        );

        int movement = 1;

        if (Math.abs(turn) > Math.PI / 2) {

            turn = Utils.normalRelativeAngle(turn + Math.PI);
            movement = -1;
        }

        setTurnRightRadians(turn);

        setMaxVelocity(
            distance < 170 ? 6 : MAX_VELOCITY
        );

        setAhead(120 * movement);
    }

    // ==========================================
    // PROTEÇÃO CONTRA PAREDES
    // ==========================================

    private double wallSmoothing(double angle,
                                 int orientation) {

        double testDistance = 120;

        for (int i = 0; i < 100; i++) {

            double testX = getX()
                + Math.sin(angle) * testDistance;

            double testY = getY()
                + Math.cos(angle) * testDistance;

            boolean safe =
                testX > WALL_MARGIN &&
                testX < getBattleFieldWidth() - WALL_MARGIN &&
                testY > WALL_MARGIN &&
                testY < getBattleFieldHeight() - WALL_MARGIN;

            if (safe) {
                break;
            }

            angle += orientation * 0.05;
        }

        return angle;
    }

    // ==========================================
    // EVENTOS DE COMBATE
    // ==========================================

    public void onHitByBullet(HitByBulletEvent e) {

        if (e.getName() != null && isTeammate(e.getName())) {
            return;
        }

        direction *= -1;

        setTurnRight(75 * direction);
        setAhead(120 * direction);
    }

    public void onHitWall(HitWallEvent e) {

        direction *= -1;

        setBack(100);
        setTurnRight(90 * direction);
    }

    public void onHitRobot(HitRobotEvent e) {

        // Se for o Guardian, inicia uma manobra de afastamento.
        if (e.getName() != null && isTeammate(e.getName())) {

            double anguloAliado =
                getHeadingRadians() + e.getBearingRadians();

            allyEscapeAngle = anguloAliado + Math.PI;
            allyEscapeUntil = getTime() + 12;

            evitarColisaoAliado();
            return;
        }

        // Se for inimigo, mantém o comportamento ofensivo.
        if (getGunHeat() == 0 && getEnergy() > 2) {
            setFire(2);
        }

        direction *= -1;

        setBack(90);
        setTurnRight(60 * direction);
    }

    // ==========================================
    // MORTE DE UM INIMIGO
    // ==========================================

    public void onRobotDeath(RobotDeathEvent e) {

        enemies.remove(e.getName());
        teamIntel.remove(e.getName());

        if (e.getName().equals(currentTarget)) {

            currentTarget = null;
            selecionarAlvo();
        }
    }
}
