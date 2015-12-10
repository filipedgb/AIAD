package agents;

import jade.core.Agent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Scanner;

import hospital.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import utils.DynamicList;
import utils.Utilities;

import javax.swing.*;

import static java.lang.Thread.sleep;

public class RecursoAgent extends Agent {
    private Exame currentExame;
    private ArrayList<Exame> examesPossiveis; //lista de exames que o recurso consegue tratar

    private ArrayList<AID> pacientes;

    private String recursoName;
    private DynamicList List;

    private boolean available = true;
    private AID ultimoPaciente;

    private long start;

    @Override
    protected void setup() {
        super.setup();

        System.out.println("RecursoAgent.setup");
        System.out.println("Usage: Recurso([String nome_do_exame]+)");

        recursoName = this.getName().split("@")[0];

        //Criacao da GUI
        List = new DynamicList();
        JFrame frame = new JFrame(recursoName);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(List);
        frame.setSize(300, 250);
        frame.setVisible(true);

        examesPossiveis = new ArrayList<Exame>();

        // Vai buscar o nome do exame ao argumento passado
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            for (int i = 0; i< args.length;i++) {
                examesPossiveis.add(new Exame((String) args[i]));
                System.out.println("RECURSO ["+recursoName+"] => Posso fazer o exame: " + examesPossiveis.get(i).getNome());
            }

            /* Esperar que o sniffer abra */
            System.out.println("Press Any Key To Continue...");
            new java.util.Scanner(System.in).nextLine();

            // Vai buscar todos os pacientes que precisam de um determinado exame
            // TODO É preciso mudar para informar isto só quando estiver available e não tipo ciclo.
            addBehaviour(new TickerBehaviour(this, 2000) {
                protected void onTick() {
                    if(available) {
                        for (Exame e : examesPossiveis) {
                            // De seguida é feito update da lista dos pacientes porque podem entrar pacientes a qq hora
                            DFAgentDescription template = new DFAgentDescription();
                            ServiceDescription sd = new ServiceDescription();
                            sd.setType("preciso-exame-" + e.getNome());
                            template.addServices(sd);
                            try {
                                // O hospital vai procurar todos os pacientes que "ofereçam um serviço" do tipo "preciso-exame"
                                DFAgentDescription[] result = DFService.search(myAgent, template);

                                for (int j = 0; j < result.length; j++) {
                                    pacientes.add(result[j].getName());
                                    System.out.println("AGENTE: " + result[j].getName() + " precisa de: " + e.getNome());
                                    System.out.println("Pacientes 1 length: " + pacientes.size());
                                }
                            } catch (FIPAException fe) {
                                fe.printStackTrace();
                            }

                            System.out.println("Pacientes 5 length: " + pacientes.size());

                        }

                        System.out.println("Pacientes length 3: " + pacientes.size());
                        // Perform the request
                        // apenas se alguem precisar dos exames que eu forneço
                        if (pacientes.size() > 0) {
                            System.out.println("Ah e tal vou começar um novo request performer.");
                            myAgent.addBehaviour(new RequestPerformer());
                        }
                    } else {
                        double elapsedTime = (System.nanoTime() - start) / 1000000.0;

                        System.out.println("Elapsed time DOUTOR: " + elapsedTime);
                        System.out.println("Tempo do exame: " + currentExame.getTempo());

                        if (elapsedTime > currentExame.getTempo() && currentExame != null) {
                            available = true;
                            System.out.println("RECURSO ["+recursoName+"] => Já terminei o exame posso fazer outro");
                            List.addMessage2(currentExame.getNome(),String.valueOf(currentExame.getTempo()),"Paciente","Livre");
                            ACLMessage order = new ACLMessage(ACLMessage.INFORM);
                            order.addReceiver(ultimoPaciente);
                            order.setContent(currentExame.toString());
                            myAgent.send(order);

                            currentExame = null;

                        }
                    }
                }
            });

        }
        else {
            // Make the agent terminate
            System.out.println("RECURSO ["+recursoName+"] => Não existe esse tipo de exame");
            doDelete();
        }
    }

    // Put agent clean-up operations here
    protected void takeDown() {
        try {
            DFService.deregister(this);
        }
        catch (FIPAException fe) {
            fe.printStackTrace();
        }

        System.out.println("Recurso-agent "+recursoName+" terminating.");
    }

    private class RequestPerformer extends Behaviour {
        private AID maisUrgente; // O agente paciente que faz a bid mais alta (ie que tem mais urgência)
        private double maisUrgenteVal; // O valor da maior oferta
        private int repliesCnt = 0; // O número de respostas de pacientes
        private MessageTemplate mt; // O template para receber respostas
        private int step = 0;
        private Date maisAntigoVal;
        private AID maisAntigo;

        public void action() {
            switch (step) {
                case 0:
                    System.out.println("Exames possíveis length: " + examesPossiveis);
                    for (Exame e : examesPossiveis) {
                        // Mandar um cfp a todos os pacientes
                        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                        System.out.println("Pacientes 2 length: " + pacientes.size());

                        for (AID paciente : pacientes) {
                            System.out.println("Vou mandar mensagem para o paciente: " + paciente.getName());
                            cfp.addReceiver(paciente);
                        }
                        cfp.setContent(e.getNome());
                        cfp.setConversationId("oferta-exame");
                        cfp.setReplyWith("cfp" + System.currentTimeMillis()); // Unique value
                        myAgent.send(cfp);
                        // Prepare the template to get proposals
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("oferta-exame"),
                                MessageTemplate.MatchInReplyTo(cfp.getReplyWith()));
                    }
                    step = 1;
                    break;
                case 1:
                    // Recebe de todos os seus pacientes a sua urgencia/data de inicio
                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        // Recebeu resposta
                        if (reply.getPerformative() == ACLMessage.PROPOSE) {
                            double urgencia = 0;

                            Date dataChegada = new Date();
                            String[] resposta = new String[] {};
                            try {
                                resposta = reply.getContent().split("\n");
                                System.out.println("RECURSO ["+recursoName+"] => RESPOSTA: [0]=> " +resposta[0] + "[1]=> " + resposta[1]);

                                if(Utilities.FIRST_COME_FIRST_SERVE)
                                    dataChegada = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse(resposta[0]);
                                else urgencia = Double.parseDouble(resposta[0]);

                                if(!examesPossiveis.contains(new Exame(resposta[1])) ){
                                    System.out.println("RECURSO ["+recursoName+"] não faz " + resposta[1]);
                                    break;
                                }
                                repliesCnt++;

                                System.out.println("RECURSO ["+recursoName+"] => Resposta do paciente com exame: " + resposta[1]);
                            }catch(Exception e) {
                                System.out.println(e.getMessage());
                            }

                            if(Utilities.FIRST_COME_FIRST_SERVE) {
                                // Escolhe o 1º que chegou - mais antigo
                                if (maisAntigo == null || dataChegada.before(maisAntigoVal)) {
                                    maisAntigoVal = dataChegada;
                                    maisAntigo = reply.getSender();
                                    currentExame = new Exame(resposta[1]);
                                }
                            }
                            else {
                                // Escolhe o mais urgente
                                if (maisUrgente == null || urgencia > maisUrgenteVal) {
                                    //Escolhe a melhor "oferta", isto é o paciente mais urgente
                                    maisUrgenteVal = urgencia;
                                    maisUrgente = reply.getSender();
                                    currentExame = new Exame(resposta[1]);
                                }
                            }
                        } else if(reply.getPerformative() == ACLMessage.REFUSE) {
                            repliesCnt++;
                        }

                        //repliesCn++t;
                        System.out.println("Reply count: " + Integer.toString(repliesCnt));
                        System.out.println("Numero Total de pacientes: " + Integer.toString(pacientes.size()));

                        if (repliesCnt >= pacientes.size()) {
                            // Já foram recebidas todas as respostas
                            System.out.println("RECURSO: Foram recebidas todas as propostas! " + Integer.toString(repliesCnt) +" / " + Integer.toString(pacientes.size()) );
                            step = 2;
                        }
                    }
                    else {
                        block();
                    }
                    break;
                case 2:
                    // Chamar o paciente para o exame segundo a melhor oferta
                    ACLMessage order = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);

                    if(Utilities.FIRST_COME_FIRST_SERVE) {
                        order.addReceiver(maisAntigo);
                        System.out.println("RECURSO [" + recursoName + "] => Vai dizer ao paciente [" + maisAntigo.getName().split("@")[0] + "] que aceita fazer exame: " + currentExame.toString());
                    }
                    else {
                        order.addReceiver(maisUrgente);
                        System.out.println("RECURSO ["+recursoName+"] => Vai dizer ao paciente [" + maisUrgente.getName().split("@")[0] + "] que aceita fazer exame: " + currentExame.toString());
                    }

                    order.setContent(currentExame.toString());
                    order.setConversationId("oferta-exame");
                    order.setReplyWith("order"+System.currentTimeMillis());
                    myAgent.send(order);
                    // Prepare the template to get the purchase order reply
                    mt = MessageTemplate.and(MessageTemplate.MatchConversationId("oferta-exame"),
                            MessageTemplate.MatchInReplyTo(order.getReplyWith()));
                    step = 3;
                    break;
                case 3:
                    // Receber a resposta ao chamamento
                    reply = myAgent.receive(mt);
                    if (reply != null) {
                        // Purchase order reply received
                        if (reply.getPerformative() == ACLMessage.CONFIRM) {
                            ultimoPaciente = reply.getSender();
                            String exame = reply.getContent().split(":")[1];
                            if(!currentExame.getNome().equals(exame))
                                System.err.println("Exames do not match!! " + currentExame.toString() + " vs " + exame);

                            else {//pacient accepted exame
                                // recurso agents, blocks while performing the exam
                                System.out.println("RECURSO ["+recursoName+"] => Vai bloquear " + currentExame.getTempo() + "ms");
                                available = false;
                                start = System.nanoTime();
                                List.addMessage2(currentExame.getNome(),String.valueOf(currentExame.getNome()),"Paciente","Ocupado");
                            }
                            step = 4;
                        } else if(reply.getPerformative() == ACLMessage.REFUSE) {
                            // pacient is occupied
                            step = 4;
                        }

                    }
                    else block();
                    break;
            }
        }
        public boolean done() {
            return ((step == 2 && (maisUrgente == null && maisAntigo == null)) || step == 4);
        }
    }

    /*
     *   Getters and setters
     */
    public boolean isAvailable() {
        return available;
    }

    public ArrayList<Exame> getExamesPossiveis() {
        return examesPossiveis;
    }

    public void setExamesPossiveis(ArrayList<Exame> examesPossiveis) {
        this.examesPossiveis = examesPossiveis;
    }

    public Exame getCurrentExame() {
        return currentExame;
    }

    public void setCurrentExame(Exame currentExame) {
        this.currentExame = currentExame;
    }
}

