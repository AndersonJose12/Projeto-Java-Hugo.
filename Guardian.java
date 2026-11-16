package paragon;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.io.IOException;

public class Guardian extends TeamRobot {

    // Ajustes de movimentação e formação da equipe Paragon
    private static final double VELOCIDADE = 6;
    private static final double MARGEM_PAREDE = 90;
    private static final double DISTANCIA_ESCOLTA = 230;
    private static final double DISTANCIA_MINIMA_DUMMY = 155;
    private static final double DISTANCIA_PROTECAO = 170;
    private static final double DISTANCIA_LATERAL = 155;
    private static final double MARGEM_LINHA_TIRO = 75;
    private static final double DISTANCIA_AMEACA_LOCAL = 300;

    private static final int IDADE_MAXIMA_DUMMY = 12;
    private static final int IDADE_MAXIMA_ALVO = 10;
    private static final int IDADE_MAXIMA_LEITURA = 12;
    private static final int IDADE_MAXIMA_AMEACA = 15;
    private static final int INTERVALO_POSICAO = 4;

    private int ladoOrbita = 1;
    private int ladoEsquiva = 1;

    // Última posição recebida do Dummy
    private double dummyX;
    private double dummyY;
    private long ultimaPosicaoDummy = -1000;

    // Alvo que o Dummy escolheu para o principal ataque
    private String alvoPrioritario = null;
    private double alvoX, alvoY, alvoEnergia;
    private double alvoDirecao, alvoVelocidade, alvoVelocidadeGiro;
    private double direcaoAnteriorAlvo;
    private long ultimaMensagemAlvo = -1000;
    private long ultimaLeituraAlvo = -1000;
    private long ultimaInformacaoPosicaoAlvo = -1000;
    private boolean temDadosAlvo = false;

    // Um inimigo próximo pode receber prioridade temporária para autodefesa
    private String ameacaLocal = null;
    private double ameacaX, ameacaY, energiaAmeaca;
    private double direcaoAmeaca, velocidadeAmeaca, giroAmeaca;
    private double direcaoAnteriorAmeaca;
    private double distanciaAmeaca;
    private long ultimaLeituraAmeaca = -1000;
    private long ameacaAte = -1;
    private boolean temDadosAmeaca = false;

    private double anguloPatrulha;
    private long proximaMudancaPatrulha = 0;
    private long proximaAtualizacaoMovimento = 0;

    // Controle de esquiva, paredes e afastamento do aliado
    private double anguloEsquiva;
    private long esquivaAte = -1;
    private long ultimaEsquiva = -1000;
    private long fugaParedeAte = -1;
    private double anguloFugaAliado;
    private long fugaAliadoAte = -1;

    private long ultimaTransmissaoInimigo = -1000;
    private long ultimaTransmissaoPosicao = -1000;

    public void run() {
        setBodyColor(Color.BLUE);
        setGunColor(Color.GRAY);
        setRadarColor(Color.CYAN);
        setBulletColor(Color.GREEN);

        setMaxVelocity(VELOCIDADE);
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        // Verificação contínua para encontrar inimigos em qualquer direção.
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        anguloPatrulha = getHeadingRadians();

        while (true) {
            compartilharPosicao();

            if (getTime() >= proximaAtualizacaoMovimento) {
                controlarMovimento();
                proximaAtualizacaoMovimento = getTime() + 4;
            }

            atualizarMiraEAtacar();
            execute();
        }
    }

    // Envia a posição do Guardian usando o mesmo formato do Dummy.
    private void compartilharPosicao() {
        if (getTime() - ultimaTransmissaoPosicao < INTERVALO_POSICAO) {
            return;
        }

        try {
            broadcastMessage(new Dummy.PositionReport(
                getName(), getX(), getY(), getTime()
            ));
            ultimaTransmissaoPosicao = getTime();
        } catch (IOException ex) {
            // Se a mensagem falhar, o Guardian continua funcionando
        }
    }

    private boolean temPosicaoDummy() {
        return getTime() - ultimaPosicaoDummy <= IDADE_MAXIMA_DUMMY;
    }

    private boolean temAlvoPrioritario() {
        return alvoPrioritario != null &&
            getTime() - ultimaMensagemAlvo <= IDADE_MAXIMA_ALVO;
    }

    private boolean temLeituraAlvoRecente() {
        return temDadosAlvo &&
            getTime() - ultimaLeituraAlvo <= IDADE_MAXIMA_LEITURA;
    }

    private boolean temAmeacaLocal() {
        return ameacaLocal != null && temDadosAmeaca &&
            getTime() - ultimaLeituraAmeaca <= IDADE_MAXIMA_AMEACA &&
            getTime() <= ameacaAte;
    }

    // Mantém o Guardian perto do parceiro, mas fora do caminho de seus disparos.
    private void controlarMovimento() {
        if (temPosicaoDummy()) {
            double distancia = Math.hypot(
                dummyX - getX(), dummyY - getY()
            );

            if (distancia < DISTANCIA_MINIMA_DUMMY) {
                double afastamento;
                if (distancia < 1) {
                    afastamento = getHeadingRadians() + Math.PI;
                } else {
                    afastamento = Math.atan2(
                        getX() - dummyX, getY() - dummyY
                    );
                }
                moverNaDirecao(afastamento, 130);
                return;
            }
        }

        if (getTime() < fugaAliadoAte) {
            moverNaDirecao(anguloFugaAliado, 110);
            return;
        }

        if (getTime() < fugaParedeAte) {
            moverNaDirecao(anguloParaCentro(), 90);
            return;
        }

        if (getTime() < esquivaAte) {
            moverNaDirecao(anguloEsquiva, 80);
            return;
        }

        if (temPosicaoDummy()) {
            double[] destino;
            if (temAlvoPrioritario()) {
                destino = calcularPosicaoProtecao();
            } else {
                destino = calcularPosicaoEscolta();
            }
            moverParaPonto(destino[0], destino[1]);
        } else {
            if (getTime() >= proximaMudancaPatrulha) {
                anguloPatrulha += Math.toRadians(25 * ladoOrbita);
                proximaMudancaPatrulha = getTime() + 35;
            }
            moverNaDirecao(anguloPatrulha, 80);
        }
    }

    // Calcula um ponto atrás e ao lado do Dummy, em relação ao inimigo.
    private double[] calcularPosicaoProtecao() {
        double dx = alvoX - dummyX;
        double dy = alvoY - dummyY;
        double distancia = Math.hypot(dx, dy);

        if (distancia < 1) {
            return calcularPosicaoEscolta();
        }

        double direcaoX = dx / distancia;
        double direcaoY = dy / distancia;
        double lateralX = -direcaoY;
        double lateralY = direcaoX;

        double afastamentoLateral = DISTANCIA_LATERAL;
        if (guardianNaLinhaDeTiroDoDummy()) {
            afastamentoLateral += 65;
        }

        double pontoX = dummyX
            - direcaoX * DISTANCIA_PROTECAO
            + lateralX * afastamentoLateral * ladoOrbita;
        double pontoY = dummyY
            - direcaoY * DISTANCIA_PROTECAO
            + lateralY * afastamentoLateral * ladoOrbita;

        return new double[] { pontoX, pontoY };
    }

    // Verifica se o Guardian está entre o Dummy e o alvo
    private boolean guardianNaLinhaDeTiroDoDummy() {
        if (!temPosicaoDummy() || !temAlvoPrioritario()) {
            return false;
        }

        double dx = alvoX - dummyX;
        double dy = alvoY - dummyY;
        double comprimentoQuadrado = dx * dx + dy * dy;

        if (comprimentoQuadrado < 1) {
            return false;
        }

        double posicao = (
            (getX() - dummyX) * dx + (getY() - dummyY) * dy
        ) / comprimentoQuadrado;

        if (posicao < 0 || posicao > 1) {
            return false;
        }

        double pontoX = dummyX + posicao * dx;
        double pontoY = dummyY + posicao * dy;
        double distancia = Math.hypot(getX() - pontoX, getY() - pontoY);

        return distancia < MARGEM_LINHA_TIRO;
    }

    private double[] calcularPosicaoEscolta() {
        double dx = getX() - dummyX;
        double dy = getY() - dummyY;
        double distancia = Math.hypot(dx, dy);

        if (distancia < 1) {
            dx = Math.sin(getHeadingRadians());
            dy = Math.cos(getHeadingRadians());
            distancia = 1;
        }

        double pontoX = dummyX + (dx / distancia) * DISTANCIA_ESCOLTA;
        double pontoY = dummyY + (dy / distancia) * DISTANCIA_ESCOLTA;
        return new double[] { pontoX, pontoY };
    }

    private void moverParaPonto(double pontoX, double pontoY) {
        pontoX = Math.max(
            MARGEM_PAREDE,
            Math.min(getBattleFieldWidth() - MARGEM_PAREDE, pontoX)
        );
        pontoY = Math.max(
            MARGEM_PAREDE,
            Math.min(getBattleFieldHeight() - MARGEM_PAREDE, pontoY)
        );

        double dx = pontoX - getX();
        double dy = pontoY - getY();
        double distancia = Math.hypot(dx, dy);

        if (distancia < 35) {
            setAhead(0);
            return;
        }

        moverNaDirecao(Math.atan2(dx, dy), Math.min(distancia, 120));
    }

    private void moverNaDirecao(double anguloDesejado, double distancia) {
        anguloDesejado = corrigirRota(anguloDesejado);

        double giro = Utils.normalRelativeAngle(
            anguloDesejado - getHeadingRadians()
        );
        int sentido = 1;

        if (Math.abs(giro) > Math.PI / 2) {
            giro = Utils.normalRelativeAngle(giro + Math.PI);
            sentido = -1;
        }

        setMaxVelocity(VELOCIDADE);
        setTurnRightRadians(giro);
        setAhead(distancia * sentido);
    }

    private double corrigirRota(double angulo) {
        double futuroX = getX() + Math.sin(angulo) * 100;
        double futuroY = getY() + Math.cos(angulo) * 100;

        boolean seguro =
            futuroX > MARGEM_PAREDE &&
            futuroX < getBattleFieldWidth() - MARGEM_PAREDE &&
            futuroY > MARGEM_PAREDE &&
            futuroY < getBattleFieldHeight() - MARGEM_PAREDE;

        return seguro ? angulo : anguloParaCentro();
    }

    private double anguloParaCentro() {
        return Math.atan2(
            getBattleFieldWidth() / 2 - getX(),
            getBattleFieldHeight() / 2 - getY()
        );
    }

    // O Guardian compartilha os inimigos que encontra com o Dummy
    public void onScannedRobot(ScannedRobotEvent e) {
        if (isTeammate(e.getName())) {
            return;
        }

        double angulo = getHeadingRadians() + e.getBearingRadians();
        double x = getX() + Math.sin(angulo) * e.getDistance();
        double y = getY() + Math.cos(angulo) * e.getDistance();

        compartilharInimigo(e.getName(), x, y, e.getEnergy());

        if (temAlvoPrioritario() && e.getName().equals(alvoPrioritario)) {
            atualizarDadosAlvo(e, x, y);
        }

        // Um inimigo próximo pode interromper temporariamente o foco no alvo do Dummy
        if (e.getDistance() <= DISTANCIA_AMEACA_LOCAL ||
            (e.getName().equals(ameacaLocal) &&
             e.getDistance() <= DISTANCIA_AMEACA_LOCAL + 80)) {
            atualizarAmeacaLocal(e, x, y);
        }
    }

    private void atualizarDadosAlvo(ScannedRobotEvent e, double x, double y) {
        long agora = getTime();
        long intervalo = agora - ultimaLeituraAlvo;

        if (temDadosAlvo && intervalo > 0) {
            alvoVelocidadeGiro = Utils.normalRelativeAngle(
                e.getHeadingRadians() - direcaoAnteriorAlvo
            ) / intervalo;
            alvoVelocidadeGiro = Math.max(
                -0.2, Math.min(0.2, alvoVelocidadeGiro)
            );
        } else {
            alvoVelocidadeGiro = 0;
        }

        alvoX = x;
        alvoY = y;
        alvoEnergia = e.getEnergy();
        alvoDirecao = e.getHeadingRadians();
        alvoVelocidade = e.getVelocity();
        direcaoAnteriorAlvo = e.getHeadingRadians();
        ultimaLeituraAlvo = agora;
        temDadosAlvo = true;
    }

    // Guarda a posição e o movimento de um adversário que chegou perto
    private void atualizarAmeacaLocal(ScannedRobotEvent e, double x, double y) {
        boolean mesmoInimigo = e.getName().equals(ameacaLocal);

        boolean podeTrocar =
            !temAmeacaLocal() || mesmoInimigo ||
            e.getDistance() < distanciaAmeaca - 45;

        if (!podeTrocar) {
            return;
        }

        long agora = getTime();
        long intervalo = agora - ultimaLeituraAmeaca;

        if (mesmoInimigo && temDadosAmeaca && intervalo > 0) {
            giroAmeaca = Utils.normalRelativeAngle(
                e.getHeadingRadians() - direcaoAnteriorAmeaca
            ) / intervalo;
            giroAmeaca = Math.max(-0.2, Math.min(0.2, giroAmeaca));
        } else {
            giroAmeaca = 0;
        }

        ameacaLocal = e.getName();
        ameacaX = x;
        ameacaY = y;
        energiaAmeaca = e.getEnergy();
        direcaoAmeaca = e.getHeadingRadians();
        velocidadeAmeaca = e.getVelocity();
        direcaoAnteriorAmeaca = e.getHeadingRadians();
        distanciaAmeaca = e.getDistance();
        ultimaLeituraAmeaca = agora;
        ameacaAte = agora + IDADE_MAXIMA_AMEACA;
        temDadosAmeaca = true;
    }

    // A ameaça próxima tem prioridade e depois o Guardian volta a apoiar o Dummy
    private void atualizarMiraEAtacar() {
        double x, y, energia, direcao, velocidade, giro;
        long ultimaLeitura;

        if (temAmeacaLocal()) {
            x = ameacaX;
            y = ameacaY;
            energia = energiaAmeaca;
            direcao = direcaoAmeaca;
            velocidade = velocidadeAmeaca;
            giro = giroAmeaca;
            ultimaLeitura = ultimaLeituraAmeaca;
        } else {
            if (!temAlvoPrioritario() || !temLeituraAlvoRecente()) {
                return;
            }

            x = alvoX;
            y = alvoY;
            energia = alvoEnergia;
            direcao = alvoDirecao;
            velocidade = alvoVelocidade;
            giro = alvoVelocidadeGiro;
            ultimaLeitura = ultimaLeituraAlvo;
        }

        if (energia <= 0.05) {
            return;
        }

        double distancia = Math.hypot(x - getX(), y - getY());
        double potencia = calcularPotencia(distancia, energia);
        double velocidadeTiro = 20 - 3 * potencia;

        int tempoDecorrido = (int) Math.max(
            0, Math.min(20, getTime() - ultimaLeitura)
        );

        double[] previsto = preverPosicao(
            x, y, direcao, velocidade, giro, tempoDecorrido
        );

        // Ajusta a previsão com o tempo necessário para o disparo chegar
        for (int i = 0; i < 3; i++) {
            double distanciaTiro = Math.hypot(
                previsto[0] - getX(), previsto[1] - getY()
            );
            int tempoTiro = (int) Math.ceil(distanciaTiro / velocidadeTiro);
            previsto = preverPosicao(
                x, y, direcao, velocidade, giro,
                tempoDecorrido + tempoTiro
            );
        }

        double anguloMira = Math.atan2(
            previsto[0] - getX(), previsto[1] - getY()
        );
        double erro = Utils.normalRelativeAngle(
            anguloMira - getGunHeadingRadians()
        );

        setTurnGunRightRadians(erro);

        boolean miraAlinhada = Math.abs(erro) < Math.toRadians(4);
        boolean canhaoPronto = getGunHeat() == 0;
        boolean energiaSuficiente = getEnergy() > potencia + 0.5;
        boolean caminhoLivre = !dummyBloqueiaTiro(previsto[0], previsto[1]);

        if (miraAlinhada && canhaoPronto && energiaSuficiente && caminhoLivre) {
            setFire(potencia);
        }
    }

    // Confere se o Dummy está entre o Guardian e o ponto de impacto previsto
    private boolean dummyBloqueiaTiro(double alvoX, double alvoY) {
        if (!temPosicaoDummy()) {
            return false;
        }

        double dx = alvoX - getX();
        double dy = alvoY - getY();
        double comprimentoQuadrado = dx * dx + dy * dy;

        if (comprimentoQuadrado < 1) {
            return true;
        }

        double posicao = (
            (dummyX - getX()) * dx + (dummyY - getY()) * dy
        ) / comprimentoQuadrado;

        if (posicao <= 0 || posicao >= 1) {
            return false;
        }

        double pontoX = getX() + posicao * dx;
        double pontoY = getY() + posicao * dy;
        double distancia = Math.hypot(dummyX - pontoX, dummyY - pontoY);

        return distancia < MARGEM_LINHA_TIRO;
    }

    private double[] preverPosicao(double x, double y, double direcao,
                                   double velocidade, double giro, int passos) {
        int limite = Math.max(0, Math.min(passos, 80));

        for (int i = 0; i < limite; i++) {
            direcao += giro;
            double proximoX = x + Math.sin(direcao) * velocidade;
            double proximoY = y + Math.cos(direcao) * velocidade;

            if (proximoX < 18 ||
                proximoX > getBattleFieldWidth() - 18 ||
                proximoY < 18 ||
                proximoY > getBattleFieldHeight() - 18) {

                x = Math.max(18, Math.min(getBattleFieldWidth() - 18, proximoX));
                y = Math.max(18, Math.min(getBattleFieldHeight() - 18, proximoY));
                break;
            }

            x = proximoX;
            y = proximoY;
        }

        return new double[] { x, y };
    }

    private double calcularPotencia(double distancia, double energiaInimigo) {
        double potencia = distancia < 300 ? 1.5 : 1.0;

        if (getEnergy() < 20) {
            potencia = 1.0;
        }
        if (energiaInimigo < potencia) {
            potencia = Math.max(0.1, energiaInimigo);
        }

        return potencia;
    }

    private void compartilharInimigo(String nome, double x,
                                     double y, double energia) {
        if (getTime() - ultimaTransmissaoInimigo < 8) {
            return;
        }

        try {
            broadcastMessage(new Dummy.EnemyReport(
                getName(), nome, x, y, energia, getTime()
            ));
            ultimaTransmissaoInimigo = getTime();
        } catch (IOException ex) {
            // A próxima leitura poderá ser enviada de forma normal
        }
    }

    // Recebe a posição, o alvo e os relatórios de inimigos do Dummy
    public void onMessageReceived(MessageEvent e) {
        Object mensagem = e.getMessage();

        if (mensagem instanceof Dummy.PositionReport) {
            Dummy.PositionReport posicao = (Dummy.PositionReport) mensagem;
            if (!isTeammate(posicao.sender)) {
                return;
            }

            long idade = getTime() - posicao.time;
            if (idade >= 0 && idade <= IDADE_MAXIMA_DUMMY &&
                posicao.time >= ultimaPosicaoDummy) {
                dummyX = posicao.x;
                dummyY = posicao.y;
                ultimaPosicaoDummy = posicao.time;
            }
            return;
        }

        if (mensagem instanceof Dummy.TargetReport) {
            Dummy.TargetReport relatorio = (Dummy.TargetReport) mensagem;
            if (!isTeammate(relatorio.sender)) {
                return;
            }

            long idade = getTime() - relatorio.time;
            if (idade < 0 || idade > IDADE_MAXIMA_ALVO ||
                relatorio.time < ultimaMensagemAlvo) {
                return;
            }

            if (relatorio.target == null) {
                alvoPrioritario = null;
                temDadosAlvo = false;
                ultimaMensagemAlvo = relatorio.time;
                return;
            }

            if (isTeammate(relatorio.target)) {
                return;
            }

            boolean mudouAlvo = !relatorio.target.equals(alvoPrioritario);
            alvoPrioritario = relatorio.target;
            ultimaMensagemAlvo = relatorio.time;

            if (mudouAlvo) {
                alvoX = relatorio.x;
                alvoY = relatorio.y;
                alvoEnergia = relatorio.energy;
                ultimaInformacaoPosicaoAlvo = relatorio.time;
                temDadosAlvo = false;
                ultimaLeituraAlvo = -1000;
                alvoVelocidadeGiro = 0;
            } else if (!temLeituraAlvoRecente() &&
                       relatorio.time >= ultimaInformacaoPosicaoAlvo) {
                alvoX = relatorio.x;
                alvoY = relatorio.y;
                alvoEnergia = relatorio.energy;
                ultimaInformacaoPosicaoAlvo = relatorio.time;
            }
            return;
        }

        if (mensagem instanceof Dummy.EnemyReport) {
            Dummy.EnemyReport relatorio = (Dummy.EnemyReport) mensagem;
            if (!isTeammate(relatorio.sender) ||
                relatorio.enemy == null || isTeammate(relatorio.enemy)) {
                return;
            }

            long idade = getTime() - relatorio.time;
            if (idade < 0 || idade > 35) {
                return;
            }

            if (temAlvoPrioritario() &&
                relatorio.enemy.equals(alvoPrioritario) &&
                !temLeituraAlvoRecente() &&
                relatorio.time >= ultimaInformacaoPosicaoAlvo) {
                alvoX = relatorio.x;
                alvoY = relatorio.y;
                alvoEnergia = relatorio.energy;
                ultimaInformacaoPosicaoAlvo = relatorio.time;
            }
        }
    }

    public void onHitByBullet(HitByBulletEvent e) {
        if (e.getName() != null && isTeammate(e.getName())) {
            return;
        }
        if (getTime() - ultimaEsquiva < 8) {
            return;
        }

        ultimaEsquiva = getTime();
        double origemTiro = getHeadingRadians() + e.getBearingRadians();
        anguloEsquiva = origemTiro + ladoEsquiva * Math.PI / 2;
        ladoEsquiva *= -1;
        esquivaAte = getTime() + 6;
        proximaAtualizacaoMovimento = getTime();
    }

    public void onHitWall(HitWallEvent e) {
        fugaParedeAte = getTime() + 8;
        proximaAtualizacaoMovimento = getTime();
        ladoOrbita *= -1;
    }

    public void onHitRobot(HitRobotEvent e) {
        if (e.getName() != null && isTeammate(e.getName())) {
            double anguloAliado = getHeadingRadians() + e.getBearingRadians();
            anguloFugaAliado = anguloAliado + Math.PI;
            fugaAliadoAte = getTime() + 10;
            proximaAtualizacaoMovimento = getTime();
            return;
        }

        double anguloInimigo = getHeadingRadians() + e.getBearingRadians();
        anguloEsquiva = anguloInimigo + Math.PI;
        esquivaAte = getTime() + 6;
        proximaAtualizacaoMovimento = getTime();
    }

    public void onRobotDeath(RobotDeathEvent e) {
        if (alvoPrioritario != null && e.getName().equals(alvoPrioritario)) {
            alvoPrioritario = null;
            temDadosAlvo = false;
            ultimaMensagemAlvo = -1000;
        }

        if (ameacaLocal != null && e.getName().equals(ameacaLocal)) {
            ameacaLocal = null;
            temDadosAmeaca = false;
            ameacaAte = -1;
            ultimaLeituraAmeaca = -1000;
        }
    }
}
