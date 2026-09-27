package com.edward.laya.app;

import com.edward.laya.core.Question;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-made question sets. Instructions and option descriptions are in English because that is
 * how Laya was trained; the text being judged can be in Portuguese or any of 100+ languages.
 * Each question carries a Portuguese label for the screen.
 */
public final class Presets {

    public static final class Preset {
        public final String name;
        public final String example;
        public final List<Question> questions;
        public final Map<String, String> labels;

        Preset(String name, String example, List<Question> questions, Map<String, String> labels) {
            this.name = name;
            this.example = example;
            this.questions = questions;
            this.labels = labels;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private Presets() {
    }

    private static Map<String, String> opts(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static List<Preset> all() {
        List<Preset> out = new ArrayList<>();

        {
            List<Question> q = new ArrayList<>();
            Map<String, String> l = new LinkedHashMap<>();
            q.add(Question.choice("department", "Route this customer message to one department.", opts(
                    "billing", "Payments, charges, fees and refunds",
                    "sales", "New purchases, upgrades or pricing",
                    "support", "Product help, errors or technical issues",
                    "other", "Anything else")));
            l.put("department", "Departamento");
            q.add(Question.score("urgency", "Score how urgent this message is.",
                    Arrays.asList("low", "medium", "high")));
            l.put("urgency", "Urgência");
            q.add(Question.noul("churn_risk", "Is this customer at risk of leaving?"));
            l.put("churn_risk", "Risco de perder o cliente");
            out.add(new Preset("Triagem de atendimento", "Fui cobrado duas vezes no mesmo mês e ninguém responde. Se não resolverem hoje, vou encerrar a conta.", q, l));
        }
        {
            List<Question> q = new ArrayList<>();
            Map<String, String> l = new LinkedHashMap<>();
            q.add(Question.noul("scam", "Does this message look like a scam, phishing or social-engineering attempt?"));
            l.put("scam", "Parece golpe");
            q.add(Question.choice("tactic", "Which manipulation tactic does the message mainly use?", opts(
                    "bank_impersonation", "Pretends to be a bank, card company or government agency",
                    "urgency_threat", "Threatens blocking, fines or loss unless you act now",
                    "prize_or_offer", "Promises a prize, refund or unbelievable offer",
                    "payment_request", "Asks for a Pix, transfer, code or password",
                    "none", "No manipulation tactic")));
            l.put("tactic", "Tática");
            out.add(new Preset("Golpe / fraude", "BANCO: Seu cartão foi bloqueado por suspeita de fraude. Regularize em até 2h pelo link bit.ly/regulariza-cartao ou será cancelado.", q, l));
        }
        {
            List<Question> q = new ArrayList<>();
            Map<String, String> l = new LinkedHashMap<>();
            q.add(Question.choice("intent", "What is the corporate client mainly asking for?", opts(
                    "payments_pix", "Pix, payments, transfers or bill payment",
                    "credit", "Loans, credit lines, limits or financing",
                    "investments", "Investments, cash yield or treasury products",
                    "cash_management", "Collections, statements, reconciliation or cash management",
                    "fx_trade", "Foreign exchange, trade finance or international payments",
                    "access_security", "Login, tokens, permissions or digital signature",
                    "complaint", "A complaint about service or a problem")));
            l.put("intent", "Intenção");
            q.add(Question.noul("needs_human", "Does the client ask to talk to a human relationship manager?"));
            l.put("needs_human", "Pede um gerente humano");
            q.add(Question.score("sentiment", "Score the client's tone.",
                    Arrays.asList("angry", "neutral", "positive")));
            l.put("sentiment", "Tom");
            out.add(new Preset("Intenção de cliente PJ", "Bom dia, precisamos ampliar o limite da conta garantida antes do fechamento do mês. Consegue marcar uma call com nosso gerente?", q, l));
        }
        {
            List<Question> q = new ArrayList<>();
            Map<String, String> l = new LinkedHashMap<>();
            q.add(Question.choice("bucket", "Sort this email for its recipient.", opts(
                    "act_now", "Needs an action or decision from the recipient soon",
                    "reply", "Needs a reply but is not urgent",
                    "fyi", "Information only, no action",
                    "newsletter", "Newsletter, marketing or automated notification",
                    "junk", "Spam or irrelevant")));
            l.put("bucket", "Categoria");
            q.add(Question.noul("meeting", "Does the email ask to schedule a meeting or call?"));
            l.put("meeting", "Pede reunião");
            out.add(new Preset("Caixa de e-mail", "Edward, preciso da sua aprovação no orçamento do Q4 até quinta. Consegue revisar a planilha anexa?", q, l));
        }
        {
            List<Question> q = new ArrayList<>();
            Map<String, String> l = new LinkedHashMap<>();
            q.add(Question.choice("sentiment", "What is the overall sentiment of the text?", opts(
                    "positive", null, "neutral", null, "negative", null)));
            l.put("sentiment", "Sentimento");
            q.add(Question.score("intensity", "Score how strong the emotion is.",
                    Arrays.asList("very weak", "weak", "moderate", "strong", "very strong")));
            l.put("intensity", "Intensidade");
            out.add(new Preset("Sentimento", "O app novo ficou incrível, finalmente consigo aprovar pagamentos pelo celular!", q, l));
        }
        return out;
    }
}
